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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.plugin.PluginEvent;
import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

/**
 * Persists each plugin's enable state to JSON on disk so that restarting the assistant doesn't
 * lose user-chosen plugin activations.
 *
 * <p>Storage shape (deliberately a top-level array because the file's only job is plugin state —
 * if more keys land here later we can wrap):
 *
 * <pre>{@code
 * {
 *   "enabledPlugins": ["plugin-name-a", "plugin-name-b"],
 *   "savedAt": "2026-05-20T12:34:56Z"
 * }
 * }</pre>
 *
 * <p>Persistence is keyed by plugin <em>name</em>, not installation id (which is randomly
 * generated each install). When a plugin re-installs with the same name on next launch, we look
 * at the persisted set and re-enable it.
 *
 * <p>This store subscribes to {@link PluginManager#events()} and writes after every Enabled /
 * Disabled / Uninstalled. {@link #rehydrate} should be called once at startup, after all
 * persisted plugins are re-installed, to flip them back on.
 */
public final class EnabledPluginsStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EnabledPluginsStore.class);

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();
    private final PluginManager manager;
    private final Disposable subscription;
    private final Set<String> enabledNames =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public EnabledPluginsStore(Path file, PluginManager manager) {
        this.file = file;
        this.manager = manager;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            log.warn(
                    "Failed to ensure parent directory for plugin settings at {}: {}",
                    file,
                    e.getMessage());
        }
        loadFromDisk();
        this.subscription =
                manager.events()
                        .subscribe(
                                this::onEvent,
                                err ->
                                        log.warn(
                                                "EnabledPluginsStore event stream error: {}",
                                                err.getMessage()));
    }

    /** Set of plugin names that were enabled in the previous session. */
    public Set<String> persistedEnabledNames() {
        return Set.copyOf(enabledNames);
    }

    /**
     * After all plugins have been re-installed from their sources, call this to re-enable each
     * one whose name appears in the persisted set. Returns the list of names actually re-enabled
     * (some may have been uninstalled between sessions).
     */
    public Set<String> rehydrate() {
        Set<String> rehydrated = new LinkedHashSet<>();
        for (PluginInstallation inst : manager.list()) {
            String name = inst.metadata().name();
            if (!enabledNames.contains(name) || inst.enabled()) continue;
            try {
                manager.enable(inst.id()).block(java.time.Duration.ofSeconds(15));
                rehydrated.add(name);
                log.info("Re-enabled plugin '{}' from persisted settings", name);
            } catch (Exception e) {
                log.warn(
                        "Failed to re-enable persisted plugin '{}': {}",
                        name,
                        e.getMessage());
            }
        }
        return rehydrated;
    }

    private void onEvent(PluginEvent event) {
        if (event instanceof PluginEvent.Enabled enabled) {
            if (enabledNames.add(enabled.installation().metadata().name())) {
                saveToDisk();
            }
        } else if (event instanceof PluginEvent.Disabled disabled) {
            if (enabledNames.remove(disabled.installation().metadata().name())) {
                saveToDisk();
            }
        } else if (event instanceof PluginEvent.Uninstalled uninstalled) {
            if (enabledNames.remove(uninstalled.installation().metadata().name())) {
                saveToDisk();
            }
        }
    }

    private void loadFromDisk() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode arr = root == null ? null : root.get("enabledPlugins");
            if (arr == null || !arr.isArray()) return;
            for (JsonNode n : arr) {
                if (n.isTextual()) enabledNames.add(n.asText());
            }
            log.info(
                    "Loaded {} persisted enabled plugin(s) from {}",
                    enabledNames.size(),
                    file);
        } catch (Exception e) {
            log.warn(
                    "Failed to load enabled-plugins settings from {} (continuing with empty set): {}",
                    file,
                    e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        ObjectNode root = json.createObjectNode();
        var arr = root.putArray("enabledPlugins");
        // Sorted for deterministic file content / git-friendly diffs.
        enabledNames.stream().sorted().forEach(arr::add);
        root.put("savedAt", java.time.Instant.now().toString());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, root.toPrettyString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn(
                    "Failed to persist enabled-plugins settings to {}: {}",
                    file,
                    e.getMessage());
        }
    }

    @Override
    public void close() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }
}
