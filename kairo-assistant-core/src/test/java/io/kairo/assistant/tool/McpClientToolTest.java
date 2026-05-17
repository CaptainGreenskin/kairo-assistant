package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class McpClientToolTest {

    private final McpClientTool tool = new McpClientTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    private static HttpServer server;
    private static String serverUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            String req = new String(reqBody);
            byte[] resp;
            if (req.contains("tools/list")) {
                resp = """
                        {"jsonrpc":"2.0","id":"1","result":{"tools":[{"name":"echo","description":"Echo input"}]}}
                        """.getBytes();
            } else if (req.contains("tools/call")) {
                resp = """
                        {"jsonrpc":"2.0","id":"1","result":{"content":[{"type":"text","text":"called ok"}]}}
                        """.getBytes();
            } else {
                resp = """
                        {"jsonrpc":"2.0","id":"1","error":{"code":-32601,"message":"Method not found"}}
                        """.getBytes();
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        serverUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void actionAndUrlRequired() {
        ToolResult r = tool.execute(Map.of("action", "list_tools"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void callRequiresToolName() {
        ToolResult r = tool.execute(
                Map.of("action", "call", "server_url", serverUrl), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(
                Map.of("action", "deploy", "server_url", serverUrl), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema().properties()).containsKey("server_url");
    }

    @Test
    void listToolsReturnsResult() {
        ToolResult r = tool.execute(
                Map.of("action", "list_tools", "server_url", serverUrl), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("echo");
    }

    @Test
    void callToolReturnsResult() {
        ToolResult r = tool.execute(
                Map.of("action", "call", "server_url", serverUrl,
                        "tool_name", "echo", "arguments", "{\"input\":\"hi\"}"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("called ok");
    }

    @Test
    void unreachableServerErrors() {
        ToolResult r = tool.execute(
                Map.of("action", "list_tools", "server_url", "http://localhost:1"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Failed to list tools");
    }
}
