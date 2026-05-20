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

import io.kairo.api.tool.ToolOutcome;
import io.kairo.api.tool.ToolResult;
import io.kairo.plugin.mcp.PluginMcpRegistrar;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bookkeeping coverage for {@link PluginMcpToolDispatcher}'s degraded paths — happy-path tool
 * routing is exercised end-to-end by {@code PluginHookEndToEndTest} (where a real MCP subprocess
 * fixture answers the JSON-RPC).
 */
class PluginMcpToolDispatcherTest {

    @Test
    void unknownServerReturnsError() {
        // No plugin has registered any MCP server — dispatcher should report not-found, not throw.
        var registrar = new PluginMcpRegistrar(new NoopMcpPlugin());
        var dispatcher = new PluginMcpToolDispatcher(registrar);

        ToolResult result =
                dispatcher.dispatch("missing-server", "tool-x", Map.of()).block(Duration.ofSeconds(2));
        assertThat(result).isNotNull();
        assertThat(result.outcome()).isEqualTo(ToolOutcome.ERROR);
        assertThat(((io.kairo.api.tool.ToolOutput.Text) result.output()).content())
                .contains("no registered MCP server 'missing-server'");
    }

    @Test
    void missingArgsReturnsErrorNotNpe() {
        var registrar = new PluginMcpRegistrar(new NoopMcpPlugin());
        var dispatcher = new PluginMcpToolDispatcher(registrar);

        ToolResult missingServer = dispatcher.dispatch(null, "x", Map.of()).block(Duration.ofSeconds(1));
        assertThat(missingServer.outcome()).isEqualTo(ToolOutcome.ERROR);

        ToolResult missingTool = dispatcher.dispatch("s", "  ", Map.of()).block(Duration.ofSeconds(1));
        assertThat(missingTool.outcome()).isEqualTo(ToolOutcome.ERROR);
    }

    /** Minimal {@code McpPlugin} stub — we don't actually call register here. */
    private static final class NoopMcpPlugin implements io.kairo.api.mcp.McpPlugin {
        @Override
        public boolean supports(Object cfg) {
            return cfg instanceof io.kairo.mcp.McpServerConfig;
        }

        @Override
        public reactor.core.publisher.Mono<io.kairo.api.mcp.McpPluginRegistration> register(
                Object cfg) {
            return reactor.core.publisher.Mono.error(
                    new IllegalStateException("not used in this test"));
        }

        @Override
        public void close() {}
    }
}
