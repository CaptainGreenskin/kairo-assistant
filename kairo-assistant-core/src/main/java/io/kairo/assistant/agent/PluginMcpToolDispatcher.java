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

import io.kairo.api.mcp.McpPluginRegistration;
import io.kairo.api.mcp.McpPluginTool;
import io.kairo.api.tool.ToolResult;
import io.kairo.plugin.hook.handlers.McpToolDispatcher;
import io.kairo.plugin.mcp.PluginMcpRegistrar;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * {@link McpToolDispatcher} that routes plugin-hook {@code mcp_tool} actions to whatever stdio MCP
 * subprocess is currently registered for the named server. Server-name resolution walks {@link
 * PluginMcpRegistrar#snapshot()} on every dispatch so newly-enabled plugin servers are picked up
 * without restart.
 *
 * <p>Name resolution is global across plugins — the first plugin that registered a server with the
 * requested {@code serverName} wins. This matches Claude Code's semantics where MCP server names
 * sit in a flat namespace shared by all enabled plugins.
 *
 * <p>If the server or tool can't be found, returns a {@link ToolResult#error} rather than throwing
 * — hooks degrade gracefully (the {@code mcp_tool} action's result feeds back into the hook
 * decision JSON, so an error there just means the hook returns {@code error} instead of {@code
 * decision}).
 */
public final class PluginMcpToolDispatcher implements McpToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PluginMcpToolDispatcher.class);

    private final PluginMcpRegistrar registrar;

    public PluginMcpToolDispatcher(PluginMcpRegistrar registrar) {
        this.registrar = Objects.requireNonNull(registrar, "registrar");
    }

    @Override
    public Mono<ToolResult> dispatch(String server, String tool, Map<String, Object> input) {
        if (server == null || server.isBlank()) {
            return Mono.just(ToolResult.error("mcp_tool", "missing server name"));
        }
        if (tool == null || tool.isBlank()) {
            return Mono.just(ToolResult.error("mcp_tool", "missing tool name"));
        }

        McpPluginTool match = findTool(server, tool);
        if (match == null) {
            log.warn(
                    "Plugin hook requested mcp_tool '{}::{}' but no enabled plugin exposes that"
                            + " server/tool pair",
                    server,
                    tool);
            return Mono.just(
                    ToolResult.error(
                            "mcp_tool",
                            "no registered MCP server '" + server + "' with tool '" + tool + "'"));
        }

        Map<String, Object> args = input == null ? Map.of() : input;
        try {
            // McpPluginTool.executor() is typed Object to keep the SPI free of kairo-mcp leakage.
            // In practice it is an io.kairo.mcp.McpToolExecutor whose execute(Map, String) returns
            // a Mono<ToolResult>; if a different impl ever ships we surface that as an error.
            Object raw = match.executor();
            if (raw instanceof io.kairo.mcp.McpToolExecutor mte) {
                return mte.execute(args, "plugin-hook-" + System.nanoTime())
                        .onErrorResume(
                                err ->
                                        Mono.just(
                                                ToolResult.error(
                                                        "mcp_tool",
                                                        "execution failed: " + err.getMessage())));
            }
            return Mono.just(
                    ToolResult.error(
                            "mcp_tool",
                            "unsupported MCP executor type: " + raw.getClass().getName()));
        } catch (RuntimeException e) {
            return Mono.just(
                    ToolResult.error("mcp_tool", "executor threw: " + e.getMessage()));
        }
    }

    private McpPluginTool findTool(String server, String tool) {
        for (Map.Entry<String, List<PluginMcpRegistrar.ServerHandle>> entry :
                registrar.snapshot().entrySet()) {
            for (PluginMcpRegistrar.ServerHandle handle : entry.getValue()) {
                if (!server.equals(handle.serverName())) continue;
                McpPluginRegistration reg = handle.registration();
                for (McpPluginTool t : reg.tools()) {
                    String toolName = t.definition().name();
                    // MCP tool names sometimes come through with a server prefix (e.g.
                    // "<server>__<tool>"). Match both shapes.
                    if (tool.equals(toolName) || toolName.endsWith("__" + tool)) {
                        return t;
                    }
                }
            }
        }
        return null;
    }
}
