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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.plugin.MarketplaceCatalog;
import io.kairo.api.plugin.MarketplaceEntry;
import io.kairo.api.plugin.PluginSource;
import io.kairo.plugin.manifest.MarketplaceParser;
import io.kairo.plugin.source.HttpDownloader;
import io.kairo.plugin.source.SourceFetcherRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local registry of subscribed Claude Code plugin marketplaces. Each marketplace is a JSON
 * manifest (the {@code .claude-plugin/marketplace.json} convention from upstream) listing one or
 * more plugins available at known sources.
 *
 * <p>The REPL's {@code /plugin marketplace add|remove|list} commands wrap this store; the
 * {@code /plugin install <plugin-name>} command resolves the requested name against the union of
 * all subscribed marketplaces and hands the resulting {@link PluginSource} to the plugin manager.
 *
 * <p>Persisted at {@code <dataDir>/plugins/marketplaces.json}:
 *
 * <pre>{@code
 * {
 *   "marketplaces": [
 *     {"alias": "official",
 *      "source": "https://raw.githubusercontent.com/anthropics/claude-plugins/main/.claude-plugin/marketplace.json"},
 *     {"alias": "company-private",
 *      "source": "file:/var/lib/kairo/marketplaces/company.json"}
 *   ]
 * }
 * }</pre>
 *
 * <p>Resolution of source URLs is done at {@link #refresh()} time via {@link HttpDownloader} for
 * HTTP/HTTPS or direct {@link Files#readString} for {@code file:} URIs. Failures are logged but
 * don't kill the store — operators can recover by removing the broken alias.
 */
public final class MarketplaceStore {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceStore.class);

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();
    private final MarketplaceParser marketplaceParser = new MarketplaceParser();
    private final HttpDownloader http;
    private final ConcurrentHashMap<String, String> aliasToSource = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MarketplaceCatalog> cachedCatalogs =
            new ConcurrentHashMap<>();

    public MarketplaceStore(Path file) {
        this(file, HttpDownloader.jdk());
    }

    public MarketplaceStore(Path file, HttpDownloader http) {
        this.file = file;
        this.http = http;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            log.warn("Failed to create marketplace store dir at {}: {}", file, e.getMessage());
        }
        loadFromDisk();
    }

    /** Add a marketplace; alias must be unique. Returns true if added, false if alias collided. */
    public boolean add(String alias, String source) {
        if (aliasToSource.putIfAbsent(alias, source) != null) return false;
        saveToDisk();
        return true;
    }

    /** Remove a marketplace by alias. Returns true if found and removed. */
    public boolean remove(String alias) {
        boolean removed = aliasToSource.remove(alias) != null;
        cachedCatalogs.remove(alias);
        if (removed) saveToDisk();
        return removed;
    }

    public Map<String, String> list() {
        return Map.copyOf(aliasToSource);
    }

    /**
     * Pull a fresh copy of every marketplace's catalog. Returns alias → catalog (only includes
     * marketplaces that fetched successfully). Cached for {@link #resolvePlugin(String)}.
     */
    public Map<String, MarketplaceCatalog> refresh() {
        Map<String, MarketplaceCatalog> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : aliasToSource.entrySet()) {
            try {
                MarketplaceCatalog c = fetchAndParse(e.getValue());
                cachedCatalogs.put(e.getKey(), c);
                out.put(e.getKey(), c);
            } catch (Exception ex) {
                log.warn(
                        "Failed to refresh marketplace '{}' (source: {}): {}",
                        e.getKey(),
                        e.getValue(),
                        ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Resolve a plugin name across every cached marketplace catalog. Returns the first match
     * (search order = marketplace insertion order). Returns empty if nothing matches.
     */
    public Optional<MarketplaceLookup> resolvePlugin(String pluginName) {
        for (Map.Entry<String, MarketplaceCatalog> entry : cachedCatalogs.entrySet()) {
            for (MarketplaceEntry plugin : entry.getValue().plugins()) {
                if (plugin.name().equals(pluginName)) {
                    return Optional.of(
                            new MarketplaceLookup(
                                    entry.getKey(),
                                    plugin,
                                    entry.getValue().trustLevel()));
                }
            }
        }
        return Optional.empty();
    }

    /** All plugins across all cached marketplaces (alias → list). */
    public Map<String, List<MarketplaceEntry>> listAllPlugins() {
        Map<String, List<MarketplaceEntry>> out = new LinkedHashMap<>();
        cachedCatalogs.forEach((alias, catalog) -> out.put(alias, catalog.plugins()));
        return out;
    }

    public record MarketplaceLookup(String marketplaceAlias, MarketplaceEntry entry, String trustLevel) {
        public PluginSource source() {
            return entry.source();
        }
    }

    private MarketplaceCatalog fetchAndParse(String source) throws Exception {
        // HTTP/HTTPS via the same downloader source-fetchers use.
        if (source.startsWith("http://") || source.startsWith("https://")) {
            try (java.io.InputStream body = http.get(source)) {
                JsonNode root = json.readTree(body);
                return marketplaceParser.parseTree(root, null);
            }
        }
        // file: URI or plain path.
        Path local = source.startsWith("file:") ? Path.of(java.net.URI.create(source)) : Path.of(source);
        return marketplaceParser.parse(local);
    }

    private void loadFromDisk() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode arr = root == null ? null : root.get("marketplaces");
            if (arr == null || !arr.isArray()) return;
            for (JsonNode n : arr) {
                String alias = textOrNull(n, "alias");
                String src = textOrNull(n, "source");
                if (alias != null && src != null) aliasToSource.put(alias, src);
            }
            log.info("Loaded {} marketplace alias(es) from {}", aliasToSource.size(), file);
        } catch (Exception e) {
            log.warn("Failed to load marketplaces from {}: {}", file, e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        ObjectNode root = json.createObjectNode();
        ArrayNode arr = root.putArray("marketplaces");
        new ArrayList<>(aliasToSource.entrySet())
                .stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(
                                e -> {
                                    ObjectNode o = arr.addObject();
                                    o.put("alias", e.getKey());
                                    o.put("source", e.getValue());
                                });
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, root.toPrettyString(), StandardCharsets.UTF_8);
            Files.move(
                    tmp,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to persist marketplaces to {}: {}", file, e.getMessage());
        }
    }

    /** Use the assistant's source-fetcher registry — kept here to avoid the SourceFetcherRegistry import elsewhere. */
    @SuppressWarnings("unused")
    private static void ignoredFetcherImport(SourceFetcherRegistry unused) {
        /* future use */
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode n = parent == null ? null : parent.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }
}
