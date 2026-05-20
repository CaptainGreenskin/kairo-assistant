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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only audit log of every plugin-attributed action: hook firings, MCP tool dispatches,
 * subagent spawns. One JSON object per line (NDJSON) so operators can {@code grep} / {@code jq}
 * the log without writing a parser.
 *
 * <p>Lives at {@code <dataDir>/plugins/audit.ndjson} by default. The store is best-effort: I/O
 * errors are logged but never thrown, so a failing audit log never breaks the agent.
 */
public final class PluginAuditLogger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginAuditLogger.class);

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    public PluginAuditLogger(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            log.warn("Failed to create audit log dir for {}: {}", file, e.getMessage());
        }
    }

    public void recordHookDispatch(
            String pluginName, String eventName, String actionType, Map<String, Object> payload) {
        write(
                node -> {
                    node.put("kind", "hook");
                    node.put("plugin", pluginName);
                    node.put("event", eventName);
                    node.put("actionType", actionType);
                    if (payload != null) node.set("payload", json.valueToTree(payload));
                });
    }

    public void recordMcpToolDispatch(
            String pluginName, String serverName, String toolName, Map<String, Object> input) {
        write(
                node -> {
                    node.put("kind", "mcp_tool");
                    node.put("plugin", pluginName);
                    node.put("server", serverName);
                    node.put("tool", toolName);
                    if (input != null) node.set("input", json.valueToTree(input));
                });
    }

    public void recordSubagentDispatch(String pluginName, String prompt, String modelHint) {
        write(
                node -> {
                    node.put("kind", "subagent");
                    node.put("plugin", pluginName);
                    node.put("modelHint", modelHint);
                    // Truncate long prompts so audit lines stay readable.
                    if (prompt != null) {
                        node.put("prompt", prompt.length() > 1000 ? prompt.substring(0, 1000) + "…" : prompt);
                    }
                });
    }

    private synchronized void write(java.util.function.Consumer<ObjectNode> body) {
        ObjectNode node = json.createObjectNode();
        node.put("ts", java.time.Instant.now().toString());
        body.accept(node);
        try (BufferedWriter w =
                Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        new OpenOption[] {
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND
                        })) {
            w.write(node.toString());
            w.newLine();
        } catch (IOException e) {
            log.debug("Audit write failed for {}: {}", file, e.getMessage());
        }
    }

    @Override
    public void close() {
        // Nothing held open — synchronous writes already flushed and closed each line.
    }
}
