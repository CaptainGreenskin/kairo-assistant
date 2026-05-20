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

import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.hook.UserPromptSubmitEvent;
import io.kairo.api.plugin.PluginComponent;
import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginScope;
import io.kairo.api.plugin.PluginSource;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Verifies {@link PluginHookBridge} honours decision JSON returned by plugin hook actions:
 *
 * <ul>
 *   <li>{@code "continue": false} on PreToolUse cancels the tool call
 *   <li>{@code "tool_input": {...}} on PreToolUse replaces the input map
 *   <li>{@code "modifiedPrompt": "..."} on UserPromptSubmit rewrites the user prompt
 * </ul>
 */
class PluginHookBridgeDecisionsTest {

    @Test
    void continueFalseCancelsTheToolCall(@TempDir Path tmp) throws Exception {
        Path pluginDir = writeHookPlugin(tmp.resolve("cancel-plugin"), "PreToolUse");

        // Plugin handler returns decision {"continue": false} → bridge should cancel.
        var executor = executorReturningDecision(Map.of("continue", false));
        var manager = newManagerWithCatalog(tmp, executor);
        installAndEnable(manager, pluginDir);

        try (var catalog = new PluginHookCatalog(manager, executor)) {
            // PluginHookCatalog subscribes on construction — but the plugin was enabled before
            // the catalog existed, so we replay by toggling.
            replayEnable(manager, pluginDir);

            var bridge = new PluginHookBridge(catalog);
            PreActingEvent in = new PreActingEvent("bash", Map.of("cmd", "ls"), false);
            PreActingEvent out = bridge.onPreActing(in);
            assertThat(out.cancelled()).as("'continue: false' must cancel the tool call").isTrue();
        }
    }

    @Test
    void denyMessageAlsoCancels(@TempDir Path tmp) throws Exception {
        Path pluginDir = writeHookPlugin(tmp.resolve("deny-plugin"), "PreToolUse");
        var executor = executorReturningDecision(Map.of("deny", "policy violation"));
        var manager = newManagerWithCatalog(tmp, executor);
        installAndEnable(manager, pluginDir);

        try (var catalog = new PluginHookCatalog(manager, executor)) {
            replayEnable(manager, pluginDir);
            var bridge = new PluginHookBridge(catalog);
            PreActingEvent out =
                    bridge.onPreActing(new PreActingEvent("bash", Map.of("cmd", "rm"), false));
            assertThat(out.cancelled()).isTrue();
        }
    }

    @Test
    void toolInputDecisionReplacesInputMap(@TempDir Path tmp) throws Exception {
        Path pluginDir = writeHookPlugin(tmp.resolve("rewrite-plugin"), "PreToolUse");
        Map<String, Object> rewritten = Map.of("cmd", "ls -la", "safer", true);
        var executor = executorReturningDecision(Map.of("tool_input", rewritten));
        var manager = newManagerWithCatalog(tmp, executor);
        installAndEnable(manager, pluginDir);

        try (var catalog = new PluginHookCatalog(manager, executor)) {
            replayEnable(manager, pluginDir);
            var bridge = new PluginHookBridge(catalog);
            PreActingEvent out =
                    bridge.onPreActing(new PreActingEvent("bash", Map.of("cmd", "ls"), false));
            assertThat(out.cancelled()).isFalse();
            assertThat(out.input()).containsEntry("cmd", "ls -la").containsEntry("safer", true);
        }
    }

    @Test
    void modifiedPromptRewritesUserPromptSubmit(@TempDir Path tmp) throws Exception {
        Path pluginDir = writeHookPlugin(tmp.resolve("ups-plugin"), "UserPromptSubmit");
        var executor = executorReturningDecision(Map.of("modifiedPrompt", "[redacted]"));
        var manager = newManagerWithCatalog(tmp, executor);
        installAndEnable(manager, pluginDir);

        try (var catalog = new PluginHookCatalog(manager, executor)) {
            replayEnable(manager, pluginDir);
            var bridge = new PluginHookBridge(catalog);
            UserPromptSubmitEvent out =
                    bridge.onUserPromptSubmit(
                            new UserPromptSubmitEvent("sess-1", "my secret prompt", "/tmp"));
            assertThat(out.prompt()).isEqualTo("[redacted]");
            assertThat(out.sessionId()).isEqualTo("sess-1");
            assertThat(out.cwd()).isEqualTo("/tmp");
        }
    }

    @Test
    void emptyDecisionLeavesEventUntouched(@TempDir Path tmp) throws Exception {
        Path pluginDir = writeHookPlugin(tmp.resolve("noop-plugin"), "PreToolUse");
        var executor = executorReturningDecision(Map.of());
        var manager = newManagerWithCatalog(tmp, executor);
        installAndEnable(manager, pluginDir);

        try (var catalog = new PluginHookCatalog(manager, executor)) {
            replayEnable(manager, pluginDir);
            var bridge = new PluginHookBridge(catalog);
            PreActingEvent in = new PreActingEvent("bash", Map.of("cmd", "ls"), false);
            PreActingEvent out = bridge.onPreActing(in);
            assertThat(out).isSameAs(in);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static HookExecutor executorReturningDecision(Map<String, Object> decision) {
        HookExecutor executor = new HookExecutor();
        executor.withHandler(
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
                        return Mono.just(new HookExecutor.HookResult(0, "", "", decision));
                    }
                });
        return executor;
    }

    private static DefaultPluginManager newManagerWithCatalog(Path tmp, HookExecutor executor) {
        return new DefaultPluginManager(
                new DefaultPluginRegistry(),
                new PluginLoader(),
                tmp.resolve("data"),
                ComponentRegistrar.noOp(),
                new SourceFetcherRegistry().register(new LocalPathSourceFetcher()));
    }

    private static PluginInstallation installAndEnable(
            DefaultPluginManager manager, Path pluginDir) {
        PluginInstallation inst =
                manager.install(new PluginSource.LocalPath(pluginDir), PluginScope.USER)
                        .block(Duration.ofSeconds(5));
        manager.enable(inst.id()).block(Duration.ofSeconds(5));
        return inst;
    }

    /**
     * Disable + enable the existing installation so the catalog (constructed AFTER the original
     * install + enable) sees an Enabled event and indexes the hooks. Simpler than re-installing.
     */
    private static void replayEnable(DefaultPluginManager manager, Path pluginDir) {
        var only = manager.list().get(0);
        manager.disable(only.id()).block(Duration.ofSeconds(5));
        manager.enable(only.id()).block(Duration.ofSeconds(5));
    }

    private static Path writeHookPlugin(Path dir, String eventName) throws Exception {
        Files.createDirectories(dir.resolve(".kairo-plugin"));
        Files.writeString(
                dir.resolve(".kairo-plugin/plugin.json"),
                "{\"name\":\"" + dir.getFileName() + "\",\"version\":\"1.0.0\"}");
        Files.createDirectories(dir.resolve("hooks"));
        Files.writeString(
                dir.resolve("hooks/hooks.json"),
                "{ \"hooks\": { \""
                        + eventName
                        + "\": [ { \"matcher\": \"*\", \"hooks\": [ {\"type\":\"prompt\","
                        + " \"prompt\": \"probe\"} ] } ] } }");
        return dir;
    }
}
