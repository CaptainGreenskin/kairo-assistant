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
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

/**
 * Tracks plugin-contributed {@link PluginComponent.OutputStyleComponent}s so the assistant can
 * append their content to its system prompt at render time.
 *
 * <p>An output-style is a Markdown file under {@code output-styles/<name>.md}. Once a plugin is
 * enabled, its style block is concatenated (in plugin-install order) under a dedicated section.
 *
 * <p>Like {@link PluginHookCatalog}, this subscribes to {@link PluginManager#events()} and
 * rebuilds its catalog on Enabled / Disabled / Uninstalled.
 */
public final class OutputStyleCatalog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutputStyleCatalog.class);

    private final PluginLoader loader;
    private final Disposable subscription;

    /** pluginId → ordered list of (name, body) tuples for that plugin's output styles. */
    private final ConcurrentHashMap<String, List<Style>> byPlugin = new ConcurrentHashMap<>();

    public OutputStyleCatalog(PluginManager manager) {
        this(manager, new PluginLoader());
    }

    public OutputStyleCatalog(PluginManager manager, PluginLoader loader) {
        this.loader = loader;
        this.subscription =
                manager.events()
                        .subscribe(
                                this::onEvent,
                                err ->
                                        log.warn(
                                                "OutputStyleCatalog event stream error: {}",
                                                err.getMessage()));
    }

    private void onEvent(PluginEvent event) {
        if (event instanceof PluginEvent.Enabled enabled) {
            index(enabled.installation());
        } else if (event instanceof PluginEvent.Disabled disabled) {
            byPlugin.remove(disabled.installation().id());
        } else if (event instanceof PluginEvent.Uninstalled uninstalled) {
            byPlugin.remove(uninstalled.installation().id());
        }
    }

    private void index(PluginInstallation inst) {
        try {
            PluginManifest manifest = loader.load(inst.rootPath(), null);
            List<Style> styles = new ArrayList<>();
            for (PluginComponent c : manifest.components()) {
                if (c instanceof PluginComponent.OutputStyleComponent osc) {
                    String body = readStyleBody(osc);
                    if (body != null && !body.isBlank()) {
                        styles.add(new Style(osc.name(), body));
                    }
                }
            }
            if (styles.isEmpty()) {
                byPlugin.remove(inst.id());
                return;
            }
            byPlugin.put(inst.id(), List.copyOf(styles));
            log.info(
                    "Indexed {} output-style(s) for plugin '{}'",
                    styles.size(),
                    inst.metadata().name());
        } catch (Exception e) {
            log.warn(
                    "Failed to index output styles for plugin '{}': {}",
                    inst.metadata().name(),
                    e.getMessage());
        }
    }

    private String readStyleBody(PluginComponent.OutputStyleComponent osc) {
        try {
            if (osc.styleFile() == null || !Files.isRegularFile(osc.styleFile())) return "";
            return Files.readString(osc.styleFile());
        } catch (IOException e) {
            log.warn(
                    "Failed to read output-style file {}: {}", osc.styleFile(), e.getMessage());
            return "";
        }
    }

    /**
     * Returns the concatenated output-style block to append to the assistant's system prompt
     * (empty if no plugins contribute one). Each style block is prefixed with its name as a
     * sub-heading so the model knows where boundaries are.
     */
    public String render() {
        if (byPlugin.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        // Deterministic order across plugins by pluginId so repeated calls produce the same prompt.
        byPlugin.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e ->
                                e.getValue()
                                        .forEach(
                                                style ->
                                                        sb.append("\n### ")
                                                                .append(style.name)
                                                                .append("\n\n")
                                                                .append(style.body.trim())
                                                                .append("\n")));
        return sb.toString();
    }

    /** Diagnostic snapshot: pluginId → ordered style name list. */
    public Map<String, List<String>> snapshot() {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        byPlugin.forEach((k, v) -> out.put(k, v.stream().map(s -> s.name).toList()));
        return out;
    }

    @Override
    public void close() {
        if (subscription != null && !subscription.isDisposed()) subscription.dispose();
        byPlugin.clear();
    }

    private record Style(String name, String body) {}
}
