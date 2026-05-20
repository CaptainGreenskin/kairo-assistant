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

import io.kairo.api.plugin.PluginComponent;
import io.kairo.api.plugin.PluginEvent;
import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginManager;
import io.kairo.api.plugin.PluginManifest;
import io.kairo.plugin.PluginLoader;
import io.kairo.plugin.hook.HookExecutor;
import io.kairo.plugin.variable.PluginVariableResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Bridges plugin {@link PluginComponent.HookComponent} declarations onto a runtime-callable
 * dispatcher. The {@link io.kairo.plugin.KairoComponentRegistrar} in kairo-plugin v1.2 captures
 * hook components but does not yet auto-dispatch them — host applications need an explicit catalog
 * to fire them on the correct lifecycle event.
 *
 * <p>This catalog subscribes to the {@link PluginManager#events()} stream:
 *
 * <ul>
 *   <li>On {@link PluginEvent.Enabled}: re-parse the plugin's manifest and index its hooks by
 *       {@code event} name.
 *   <li>On {@link PluginEvent.Disabled} / {@link PluginEvent.Uninstalled}: drop the plugin's
 *       entries.
 * </ul>
 *
 * <p>Runtime code (e.g. a tool execution wrapper) calls {@link #dispatch(String, Map)} with the
 * event name (e.g. {@code "PreToolUse"}) and a payload map. Every matching action across every
 * enabled plugin executes through the supplied {@link HookExecutor}, which in turn delegates to
 * the registered {@link io.kairo.plugin.hook.HookActionHandler}s for {@code prompt} / {@code
 * agent} / {@code mcp_tool} (and the built-in {@code command} / {@code http}).
 *
 * <p>The catalog is reactive — {@link #dispatch} returns a {@link Flux} of per-action results so
 * callers can compose decisions (or just discard with {@code .then()}).
 */
public final class PluginHookCatalog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginHookCatalog.class);

    private final HookExecutor executor;
    private final PluginLoader loader;
    private final Disposable subscription;

    /** pluginId → list of HookComponent entries (one per declared event-binding in hooks.json). */
    private final ConcurrentHashMap<String, List<PluginComponent.HookComponent>> byPlugin =
            new ConcurrentHashMap<>();

    /** pluginId → (rootPath, dataPath) so we can build a PluginVariableResolver per dispatch. */
    private final ConcurrentHashMap<String, java.nio.file.Path[]> pathsByPlugin =
            new ConcurrentHashMap<>();

    public PluginHookCatalog(PluginManager pluginManager, HookExecutor executor) {
        this(pluginManager, executor, new PluginLoader());
    }

    public PluginHookCatalog(
            PluginManager pluginManager, HookExecutor executor, PluginLoader loader) {
        this.executor = executor;
        this.loader = loader;
        this.subscription =
                pluginManager
                        .events()
                        .subscribe(this::onEvent, err -> log.warn("Hook catalog event stream error", err));
    }

    private void onEvent(PluginEvent event) {
        if (event instanceof PluginEvent.Enabled enabled) {
            indexInstallation(enabled.installation());
        } else if (event instanceof PluginEvent.Disabled disabled) {
            drop(disabled.installation().id());
        } else if (event instanceof PluginEvent.Uninstalled uninstalled) {
            drop(uninstalled.installation().id());
        }
    }

    private void indexInstallation(PluginInstallation inst) {
        try {
            PluginManifest manifest = loader.load(inst.rootPath(), null);
            List<PluginComponent.HookComponent> hooks = new ArrayList<>();
            for (PluginComponent c : manifest.components()) {
                if (c instanceof PluginComponent.HookComponent h) {
                    hooks.add(h);
                }
            }
            if (hooks.isEmpty()) {
                drop(inst.id());
                return;
            }
            byPlugin.put(inst.id(), List.copyOf(hooks));
            pathsByPlugin.put(inst.id(), new java.nio.file.Path[] {inst.rootPath(), inst.dataPath()});
            log.info(
                    "Indexed {} hook binding(s) for plugin '{}'",
                    hooks.size(),
                    inst.metadata().name());
        } catch (Exception e) {
            log.warn(
                    "Failed to index hooks for plugin '{}': {}",
                    inst.metadata().name(),
                    e.getMessage());
        }
    }

    private void drop(String pluginId) {
        if (byPlugin.remove(pluginId) != null) {
            pathsByPlugin.remove(pluginId);
            log.debug("Removed hook bindings for plugin id={}", pluginId);
        }
    }

    /**
     * Fire every action of every enabled plugin's hooks whose {@code event} matches {@code
     * eventName}. {@code matcher} (tool name, etc.) is reserved for future filtering — v1 fires
     * regardless.
     */
    public Flux<HookExecutor.HookResult> dispatch(String eventName, Map<String, Object> payload) {
        if (byPlugin.isEmpty()) return Flux.empty();
        List<Mono<HookExecutor.HookResult>> calls = new ArrayList<>();
        for (Map.Entry<String, List<PluginComponent.HookComponent>> entry : byPlugin.entrySet()) {
            String pluginId = entry.getKey();
            java.nio.file.Path[] paths = pathsByPlugin.get(pluginId);
            PluginVariableResolver resolver =
                    new PluginVariableResolver(paths[0], paths[1], null);
            for (PluginComponent.HookComponent hook : entry.getValue()) {
                if (!eventName.equals(hook.event())) continue;
                for (PluginComponent.HookComponent.HookAction action : hook.actions()) {
                    calls.add(executor.execute(action, payload, resolver));
                }
            }
        }
        return Flux.concat(calls);
    }

    /** Snapshot of currently-indexed hooks per plugin id — for diagnostics / tests. */
    public Map<String, List<PluginComponent.HookComponent>> snapshot() {
        return Map.copyOf(byPlugin);
    }

    @Override
    public void close() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        byPlugin.clear();
        pathsByPlugin.clear();
    }
}
