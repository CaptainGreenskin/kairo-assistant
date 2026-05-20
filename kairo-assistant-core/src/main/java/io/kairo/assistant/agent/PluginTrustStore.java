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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks which plugins the operator has already approved (so subsequent installs / enables don't
 * re-prompt) and what trust level was attached at install time.
 *
 * <p>{@link TrustLevel} comes from a marketplace's {@code trustLevel} field if the install was
 * marketplace-driven; otherwise it defaults to {@link TrustLevel#UNKNOWN}, which is the only
 * level that triggers the confirmation prompt on first enable.
 *
 * <p>Storage shape on disk (under {@code ~/.kairo/plugins/trust.json}):
 *
 * <pre>{@code
 * {
 *   "approvals": {
 *     "plugin-name-a": {"level": "official", "approvedAt": "2026-05-20T08:00:00Z"},
 *     "plugin-name-b": {"level": "unknown",  "approvedAt": "2026-05-20T08:01:00Z"}
 *   }
 * }
 * }</pre>
 */
public final class PluginTrustStore {

    public enum TrustLevel {
        OFFICIAL,
        COMMUNITY,
        UNKNOWN;

        public static TrustLevel fromString(String raw) {
            if (raw == null) return UNKNOWN;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "official", "verified" -> OFFICIAL;
                case "community" -> COMMUNITY;
                default -> UNKNOWN;
            };
        }

        /** Whether a plugin at this level needs explicit operator approval on first enable. */
        public boolean requiresApproval() {
            return this == UNKNOWN;
        }
    }

    public record Approval(TrustLevel level, java.time.Instant approvedAt) {}

    private static final Logger log = LoggerFactory.getLogger(PluginTrustStore.class);

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<String, Approval> approvals = new ConcurrentHashMap<>();

    public PluginTrustStore(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            log.warn(
                    "Failed to ensure parent for plugin trust file at {}: {}",
                    file,
                    e.getMessage());
        }
        loadFromDisk();
    }

    public java.util.Optional<Approval> get(String pluginName) {
        return java.util.Optional.ofNullable(approvals.get(pluginName));
    }

    /** Whether the operator has approved this plugin previously. */
    public boolean isApproved(String pluginName) {
        return approvals.containsKey(pluginName);
    }

    public void recordApproval(String pluginName, TrustLevel level) {
        approvals.put(pluginName, new Approval(level, java.time.Instant.now()));
        saveToDisk();
    }

    public void revoke(String pluginName) {
        if (approvals.remove(pluginName) != null) saveToDisk();
    }

    /** Diagnostic snapshot. */
    public Map<String, Approval> snapshot() {
        return Map.copyOf(approvals);
    }

    private void loadFromDisk() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode approvalsNode = root == null ? null : root.get("approvals");
            if (approvalsNode == null || !approvalsNode.isObject()) return;
            Iterator<Map.Entry<String, JsonNode>> it = approvalsNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode body = entry.getValue();
                TrustLevel level = TrustLevel.fromString(textOrNull(body, "level"));
                String approvedAt = textOrNull(body, "approvedAt");
                java.time.Instant when =
                        approvedAt == null ? java.time.Instant.now() : java.time.Instant.parse(approvedAt);
                approvals.put(entry.getKey(), new Approval(level, when));
            }
            log.info("Loaded {} plugin approval(s) from {}", approvals.size(), file);
        } catch (Exception e) {
            log.warn("Failed to load plugin trust file {}: {}", file, e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        ObjectNode root = json.createObjectNode();
        ObjectNode approvalsNode = root.putObject("approvals");
        // Sort by plugin name for diff-friendly content.
        approvals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e -> {
                            ObjectNode body = approvalsNode.putObject(e.getKey());
                            body.put("level", e.getValue().level().name().toLowerCase());
                            body.put("approvedAt", e.getValue().approvedAt().toString());
                        });
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, root.toPrettyString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to persist plugin trust file to {}: {}", file, e.getMessage());
        }
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode n = parent == null ? null : parent.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }
}
