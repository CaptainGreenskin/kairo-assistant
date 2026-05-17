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

class HttpRequestToolTest {

    private final HttpRequestTool tool = new HttpRequestTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/data", exchange -> {
            byte[] body;
            if ("POST".equals(exchange.getRequestMethod())) {
                byte[] reqBody = exchange.getRequestBody().readAllBytes();
                body = ("Received: " + new String(reqBody)).getBytes();
            } else {
                body = "{\"status\":\"ok\"}".getBytes();
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/not-found", exchange -> {
            byte[] body = "not found".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void urlRequired() {
        ToolResult r = tool.execute(Map.of("method", "GET"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void invalidUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "not-a-url"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void blankUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.inputSchema().properties()).containsKey("url");
        assertThat(tool.inputSchema().properties()).containsKey("method");
        assertThat(tool.inputSchema().properties()).containsKey("headers");
    }

    @Test
    void getRequestReturnsBody() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/api/data"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("200");
        assertThat(r.content()).contains("{\"status\":\"ok\"}");
        assertThat(r.metadata()).containsEntry("statusCode", 200);
    }

    @Test
    void postRequestWithBody() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/api/data", "method", "POST", "body", "hello"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Received: hello");
    }

    @Test
    void customHeaders() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/api/data", "headers", "X-Custom: test-value"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void nonSuccessStatusIncluded() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/not-found"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("404");
        assertThat(r.metadata()).containsEntry("statusCode", 404);
    }
}
