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

import io.kairo.api.agent.SubagentRegistry;
import io.kairo.api.mcp.McpPlugin;
import io.kairo.api.mcp.McpPluginRegistration;
import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginScope;
import io.kairo.api.plugin.PluginSource;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.plugin.ComponentRegistrar;
import io.kairo.plugin.DefaultPluginManager;
import io.kairo.plugin.DefaultPluginRegistry;
import io.kairo.plugin.DefaultSubagentRegistry;
import io.kairo.plugin.KairoComponentRegistrar;
import io.kairo.plugin.PluginEnvironment;
import io.kairo.plugin.PluginLoader;
import io.kairo.plugin.hook.HookExecutor;
import io.kairo.plugin.mcp.PluginMcpRegistrar;
import io.kairo.plugin.source.LocalPathSourceFetcher;
import io.kairo.plugin.source.SourceFetcherRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Proves the 3 wiring gaps closed in {@link AssistantAgentFactory#buildPluginRuntime} actually
 * reach the right registries when a real plugin is installed:
 *
 * <ol>
 *   <li><b>MCP bridge</b>: a plugin declaring {@code mcpServers} triggers {@link
 *       PluginMcpRegistrar} → {@link McpPlugin#register} (we use a capturing stub instead of {@link
 *       io.kairo.mcp.spi.DefaultMcpPlugin} so the test doesn't fork subprocesses).
 *   <li><b>Subagents</b>: a plugin shipping {@code agents/*.md} ends up in {@link
 *       SubagentRegistry}.
 *   <li><b>Hook catalog</b>: a plugin shipping {@code hooks/hooks.json} ends up in the {@link
 *       PluginHookCatalog} indexed by event name, and the {@link HookExecutor} fires the action's
 *       handler when {@link PluginHookCatalog#dispatch} is called.
 * </ol>
 */
class PluginRuntimeWiringTest {

    @Test
    void allThreeGapsWiredAndExercisedByOnePlugin(@TempDir Path tmp) throws Exception {
        Path pluginDir = writeMultiComponentPlugin(tmp.resolve("multi"));

        // Capture every MCP server config the plugin emits.
        var capturedMcp = new ArrayList<io.kairo.mcp.McpServerConfig>();
        McpPlugin stubMcp =
                new McpPlugin() {
                    @Override
                    public boolean supports(Object cfg) {
                        return cfg instanceof io.kairo.mcp.McpServerConfig;
                    }

                    @Override
                    public Mono<McpPluginRegistration> register(Object cfg) {
                        capturedMcp.add((io.kairo.mcp.McpServerConfig) cfg);
                        return Mono.just(
                                new McpPluginRegistration(
                                        ((io.kairo.mcp.McpServerConfig) cfg).name(), List.of()));
                    }

                    @Override
                    public void close() {}
                };

        var skillRegistry = AssistantSkills.createRegistry();
        var mcpRegistrar = new PluginMcpRegistrar(stubMcp);
        SubagentRegistry subagentRegistry = new DefaultSubagentRegistry();
        ComponentRegistrar registrar =
                new KairoComponentRegistrar(
                        skillRegistry, mcpRegistrar, new PluginEnvironment(), subagentRegistry);

        var fetchers = new SourceFetcherRegistry().register(new LocalPathSourceFetcher());
        var manager =
                new DefaultPluginManager(
                        new DefaultPluginRegistry(),
                        new PluginLoader(),
                        tmp.resolve("data"),
                        registrar,
                        fetchers);

        // Hook handlers: capture every dispatch so we can assert it fired.
        var hookFired = new ArrayList<String>();
        HookExecutor hookExecutor = new HookExecutor();
        hookExecutor.withHandler(
                new io.kairo.plugin.hook.HookActionHandler() {
                    @Override
                    public String type() {
                        return "prompt";
                    }

                    @Override
                    public Mono<HookExecutor.HookResult> execute(
                            io.kairo.api.plugin.PluginComponent.HookComponent.HookAction action,
                            Map<String, Object> payload,
                            io.kairo.plugin.variable.PluginVariableResolver resolver) {
                        hookFired.add("prompt:" + action.config().get("prompt"));
                        return Mono.just(HookExecutor.HookResult.empty());
                    }
                });

        try (var catalog = new PluginHookCatalog(manager, hookExecutor)) {
            PluginInstallation inst =
                    manager.install(new PluginSource.LocalPath(pluginDir), PluginScope.USER)
                            .block(Duration.ofSeconds(10));
            manager.enable(inst.id()).block(Duration.ofSeconds(10));

            // Gap 1 — MCP register actually called with the substituted command.
            assertThat(capturedMcp)
                    .as("plugin's mcpServers declaration should reach McpPlugin.register")
                    .hasSize(1);
            assertThat(capturedMcp.get(0).name()).isEqualTo("demo-mcp");
            assertThat(capturedMcp.get(0).command().get(0)).doesNotContain("${");

            // Gap 2 — subagent registered by qualified name.
            assertThat(subagentRegistry.list())
                    .as("plugin's agents/*.md should reach SubagentRegistry")
                    .extracting(io.kairo.api.agent.SubagentDefinition::name)
                    .contains("helper");

            // Gap 3 — hooks indexed by event name and dispatchable.
            assertThat(catalog.snapshot()).containsKey(inst.id());
            catalog.dispatch("PreToolUse", Map.of("tool", "bash")).blockLast(Duration.ofSeconds(5));
            assertThat(hookFired)
                    .as("PreToolUse dispatch should fire the plugin's prompt action")
                    .anyMatch(s -> s.startsWith("prompt:audit "));

            // Disable drops the hook catalog index.
            manager.disable(inst.id()).block(Duration.ofSeconds(5));
            assertThat(catalog.snapshot()).doesNotContainKey(inst.id());
        }
    }

    /**
     * Writes a self-contained plugin with: one mcpServers entry, one agents/*.md, one
     * hooks/hooks.json binding a PreToolUse → prompt action.
     */
    private static Path writeMultiComponentPlugin(Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".kairo-plugin"));
        Files.writeString(
                dir.resolve(".kairo-plugin/plugin.json"),
                """
                {
                  "name": "multi-component",
                  "version": "1.0.0",
                  "mcpServers": {
                    "demo-mcp": {
                      "command": "${KAIRO_PLUGIN_ROOT}/scripts/echo.sh",
                      "args": ["--once"],
                      "env": {"DEMO": "yes"}
                    }
                  }
                }
                """);
        Files.createDirectories(dir.resolve("agents"));
        Files.writeString(
                dir.resolve("agents/helper.md"),
                """
                ---
                name: helper
                description: Helper subagent for tests
                model: claude-sonnet-4-20250514
                tools: [bash, read]
                ---
                You are a helper.
                """);
        Files.createDirectories(dir.resolve("hooks"));
        Files.writeString(
                dir.resolve("hooks/hooks.json"),
                """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "*",
                        "hooks": [
                          {"type": "prompt", "prompt": "audit ${tool_name}"}
                        ]
                      }
                    ]
                  }
                }
                """);
        return dir;
    }
}
