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
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                body = "deleted".getBytes();
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                byte[] reqBody = exchange.getRequestBody().readAllBytes();
                body = ("Updated: " + new String(reqBody)).getBytes();
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
        server.createContext("/echo-headers", exchange -> {
            String custom = exchange.getRequestHeaders().getFirst("X-Custom");
            byte[] body = ("header=" + custom).getBytes();
            exchange.sendResponseHeaders(200, body.length);
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
        assertThat(r.content()).contains("url");
    }

    @Test
    void invalidUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "not-a-url"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Request failed");
    }

    @Test
    void blankUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
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
    void putRequestWithBody() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/api/data", "method", "PUT", "body", "update-data"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Updated: update-data");
    }

    @Test
    void deleteRequest() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/api/data", "method", "DELETE"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("deleted");
    }

    @Test
    void customHeaders() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/echo-headers", "headers", "X-Custom: test-value"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("header=test-value");
    }

    @Test
    void multipleHeaders() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/api/data", "headers", "Accept: application/json\nX-Token: abc"),
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

    @Test
    void defaultMethodIsGet() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/api/data"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("{\"status\":\"ok\"}");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("url");
        assertThat(schema.properties()).containsKey("url");
        assertThat(schema.properties()).containsKey("method");
        assertThat(schema.properties()).containsKey("headers");
        assertThat(schema.properties()).containsKey("body");
        assertThat(schema.properties()).containsKey("timeout");
    }

    @Test
    void toolAnnotation() {
        var ann = HttpRequestTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("http_request");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.EXTERNAL);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }
}
