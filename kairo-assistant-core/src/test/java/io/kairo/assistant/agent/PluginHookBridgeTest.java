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
import io.kairo.api.plugin.PluginEvent;
import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginScope;
import io.kairo.api.plugin.PluginSource;
import io.kairo.plugin.ComponentRegistrar;
import io.kairo.plugin.DefaultPluginManager;
import io.kairo.plugin.DefaultPluginRegistry;
import io.kairo.plugin.PluginLoader;
import io.kairo.plugin.hook.HookExecutor;
import io.kairo.plugin.source.LocalPathSourceFetcher;
import io.kairo.plugin.source.SourceFetcherRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Verifies that {@link PluginHookBridge} translates kairo lifecycle events into Claude Code
 * plugin event names, and that they reach {@link PluginHookCatalog#dispatch} via the captured
 * {@link HookExecutor}. We avoid spinning up a real Agent here — the bridge can be invoked
 * directly with a plain event record, which lets us assert the catalog dispatch happens.
 */
class PluginHookBridgeTest {

    @Test
    void preActingMapsToPreToolUseAndFires(@TempDir Path tmp) throws Exception {
        Path pluginDir = writePreToolUsePlugin(tmp);

        // Capturing executor: every hook call appends a marker so we can assert it fired.
        var fired = new ArrayList<String>();
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
                        fired.add(
                                action.config().get("prompt")
                                        + " | tool="
                                        + payload.get("tool_name"));
                        return Mono.just(HookExecutor.HookResult.empty());
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
            // ComponentRegistrar.noOp doesn't bind anything, but PluginHookCatalog watches the
            // event stream — we still need an Enabled event for the catalog to index the plugin.
            // Drive it manually because the no-op registrar succeeds trivially.
            manager.enable(inst.id()).block(Duration.ofSeconds(5));
            assertThat(catalog.snapshot()).containsKey(inst.id());

            PluginHookBridge bridge = new PluginHookBridge(catalog);
            bridge.onPreActing(new PreActingEvent("bash", Map.of("command", "ls"), false));

            // Bridge fires fire-and-forget; give the reactive chain a beat to drain.
            for (int i = 0; i < 50 && fired.isEmpty(); i++) {
                Thread.sleep(20);
            }
            assertThat(fired)
                    .as("PreActing should have translated to PreToolUse + dispatched the prompt action")
                    .anyMatch(s -> s.startsWith("inspect tool=bash") || s.startsWith("inspect | tool=bash"));
        }
    }

    @Test
    void disposingHookCatalogStopsReceivingPluginEvents(@TempDir Path tmp) throws Exception {
        // Closing the catalog disposes the subscription; subsequent PluginManager events shouldn't
        // index anything.
        var manager =
                new DefaultPluginManager(
                        new DefaultPluginRegistry(),
                        new PluginLoader(),
                        tmp.resolve("data"),
                        ComponentRegistrar.noOp(),
                        new SourceFetcherRegistry().register(new LocalPathSourceFetcher()));

        var catalog = new PluginHookCatalog(manager, new HookExecutor());
        catalog.close();

        // Push a synthetic Enabled event by installing + enabling a plugin AFTER close.
        Path pluginDir = writePreToolUsePlugin(tmp.resolve("after"));
        PluginInstallation inst =
                manager.install(new PluginSource.LocalPath(pluginDir), PluginScope.USER)
                        .block(Duration.ofSeconds(5));
        manager.enable(inst.id()).block(Duration.ofSeconds(5));

        assertThat(catalog.snapshot())
                .as("closed catalog should not index new plugin events")
                .isEmpty();
    }

    /** Writes a plugin with one PreToolUse hook → prompt action. Caller supplies the parent dir. */
    private static Path writePreToolUsePlugin(Path tmp) throws Exception {
        Path dir = tmp.resolve("pretool-plugin");
        Files.createDirectories(dir.resolve(".kairo-plugin"));
        Files.writeString(
                dir.resolve(".kairo-plugin/plugin.json"),
                "{\"name\":\"pretool\",\"version\":\"1.0.0\"}");
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
                          {"type": "prompt", "prompt": "inspect"}
                        ]
                      }
                    ]
                  }
                }
                """);
        return dir;
    }

    /** Drop the temp parent into a uniquely-named subdirectory so two calls don't clash. */
    private static Path writePreToolUsePlugin(java.nio.file.Path parent, String unused)
            throws Exception {
        // Unused overload kept in case future callers need a second fixture variant.
        return writePreToolUsePlugin(parent);
    }
}
