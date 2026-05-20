/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.plugin.PluginComponent;
import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginScope;
import io.kairo.api.plugin.PluginSource;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.agent.AgentBuilder;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import io.kairo.plugin.ComponentRegistrar;
import io.kairo.plugin.DefaultPluginManager;
import io.kairo.plugin.DefaultPluginRegistry;
import io.kairo.plugin.PluginLoader;
import io.kairo.plugin.hook.HookActionHandler;
import io.kairo.plugin.hook.HookExecutor;
import io.kairo.plugin.source.LocalPathSourceFetcher;
import io.kairo.plugin.source.SourceFetcherRegistry;
import io.kairo.plugin.variable.PluginVariableResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The promised end-to-end test: a real Agent (via {@link AgentBuilder}) running a real ReAct
 * turn, a real {@link DefaultToolExecutor}, a {@link PluginHookBridge} on the {@code HookChain},
 * and a plugin whose {@code PreToolUse} hook returns {@code {"continue": false}}. Asserts the
 * tool was never invoked.
 *
 * <p>This is the most important honesty-check on the entire plugin runtime — earlier tests stop
 * at "bridge returned PreActingEvent(cancelled=true)" without verifying ToolPhase actually
 * honours the flag. Here we count tool invocations and require zero.
 */
class PluginHookE2ECancelTest {

    @Test
    void pluginHookContinueFalseStopsRealBashCallDuringAgentRun(@TempDir Path tmp) throws Exception {
        // 1. Plugin: PreToolUse → prompt action; handler returns {continue:false}.
        Path pluginDir = writeCancellingPlugin(tmp.resolve("denier"));

        // 2. Tool executor with one tool we'll count invocations for.
        AtomicInteger toolInvocations = new AtomicInteger();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ToolDefinition def =
                new ToolDefinition(
                        "danger",
                        "would do something dangerous",
                        ToolCategory.GENERAL,
                        new JsonSchema("object", null, null, null),
                        SyncTool.class);
        registry.register(def);
        registry.registerInstance(
                "danger",
                (SyncTool)
                        (input, ctx) -> {
                            toolInvocations.incrementAndGet();
                            return Mono.just(ToolResult.success("danger", "executed"));
                        });
        DefaultToolExecutor executor =
                new DefaultToolExecutor(registry, new DefaultPermissionGuard());

        // 3. Plugin manager + hook executor returning {continue:false} on every prompt action.
        HookExecutor hookExecutor = new HookExecutor();
        hookExecutor.withHandler(
                new HookActionHandler() {
                    @Override
                    public String type() {
                        return "prompt";
                    }

                    @Override
                    public Mono<HookExecutor.HookResult> execute(
                            PluginComponent.HookComponent.HookAction action,
                            Map<String, Object> payload,
                            PluginVariableResolver resolver) {
                        return Mono.just(
                                new HookExecutor.HookResult(
                                        0, "{\"continue\":false}", "", Map.of("continue", false)));
                    }
                });

        var manager =
                new DefaultPluginManager(
                        new DefaultPluginRegistry(),
                        new PluginLoader(),
                        tmp.resolve("data"),
                        ComponentRegistrar.noOp(),
                        new SourceFetcherRegistry().register(new LocalPathSourceFetcher()));

        try (var catalog = new PluginHookCatalog(manager, hookExecutor)) {
            PluginInstallation inst =
                    manager.install(new PluginSource.LocalPath(pluginDir), PluginScope.USER)
                            .block(Duration.ofSeconds(5));
            manager.enable(inst.id()).block(Duration.ofSeconds(5));
            assertThat(catalog.snapshot()).containsKey(inst.id());

            // 4. Build a fake ModelProvider that issues one bash tool call, then a final text
            // turn so the ReAct loop exits.
            ModelProvider scriptedModel = new ScriptedModelProvider(toolInvocations);

            PluginHookBridge bridge = new PluginHookBridge(catalog);

            Agent agent =
                    AgentBuilder.create()
                            .name("e2e-cancel")
                            .model(scriptedModel)
                            .modelName("scripted")
                            .tools(registry)
                            .toolExecutor(executor)
                            .systemPrompt("you are an agent")
                            .maxIterations(5)
                            .streaming(false)
                            .hook(bridge)
                            .build();

            Msg reply =
                    agent.call(Msg.of(MsgRole.USER, "do something dangerous"))
                            .block(Duration.ofSeconds(20));

            assertThat(reply).isNotNull();
            assertThat(toolInvocations.get())
                    .as("tool MUST NOT be invoked when plugin hook says continue:false")
                    .isZero();
        }
    }

    /** Issues one bash-style tool call on turn 1, then a final answer on turn 2. */
    private static final class ScriptedModelProvider implements ModelProvider {
        private final AtomicInteger turn = new AtomicInteger();
        private final AtomicInteger ignoredToolInvocations;

        ScriptedModelProvider(AtomicInteger ignoredToolInvocations) {
            this.ignoredToolInvocations = ignoredToolInvocations;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            int t = turn.incrementAndGet();
            if (t == 1) {
                // Issue a tool call.
                return Mono.just(
                        new ModelResponse(
                                "resp-" + t,
                                List.of(
                                        new Content.ToolUseContent(
                                                "tool-" + UUID.randomUUID(),
                                                "danger",
                                                Map.of("arg", "rm -rf /"))),
                                new ModelResponse.Usage(5, 5, 10, 0),
                                ModelResponse.StopReason.TOOL_USE,
                                "scripted"));
            }
            // After the tool call is cancelled, return a final text reply so ReAct exits.
            return Mono.just(
                    new ModelResponse(
                            "resp-final",
                            List.of(new Content.TextContent("aborted")),
                            new ModelResponse.Usage(5, 5, 10, 0),
                            ModelResponse.StopReason.END_TURN,
                            "scripted"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return call(messages, config).flux();
        }
    }

    private static Path writeCancellingPlugin(Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".kairo-plugin"));
        Files.writeString(
                dir.resolve(".kairo-plugin/plugin.json"),
                "{\"name\":\"denier\",\"version\":\"1.0.0\"}");
        Files.createDirectories(dir.resolve("hooks"));
        Files.writeString(
                dir.resolve("hooks/hooks.json"),
                "{ \"hooks\": { \"PreToolUse\": [ { \"matcher\": \"*\", \"hooks\": [ {\"type\":\"prompt\","
                        + " \"prompt\": \"is this safe?\"} ] } ] } }");
        return dir;
    }
}
