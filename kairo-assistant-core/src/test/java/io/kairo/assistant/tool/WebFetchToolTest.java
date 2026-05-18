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

class WebFetchToolTest {

    private final WebFetchTool tool = new WebFetchTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hello", exchange -> {
            byte[] body = "Hello from test server".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/large", exchange -> {
            byte[] body = "X".repeat(60_000).getBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/json", exchange -> {
            byte[] body = "{\"key\":\"value\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/error500", exchange -> {
            byte[] body = "internal error".getBytes();
            exchange.sendResponseHeaders(500, body.length);
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
        ToolResult r = tool.execute(Map.of(), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("url");
    }

    @Test
    void blankUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void invalidUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "not-a-url"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Fetch failed");
    }

    @Test
    void fetchReturnsBody() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/hello"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Hello from test server");
    }

    @Test
    void fetchIncludesMetadata() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/hello"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.metadata()).containsEntry("statusCode", 200);
        assertThat(r.metadata()).containsEntry("truncated", false);
    }

    @Test
    void metadataIncludesContentType() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/json"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.metadata().get("contentType").toString()).contains("application/json");
    }

    @Test
    void largeResponseIsTruncated() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/large"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.metadata()).containsEntry("truncated", true);
        assertThat(r.content()).contains("truncated");
    }

    @Test
    void customHeadersSent() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/hello", "headers", "Accept: text/plain"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void multipleHeaders() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/hello", "headers", "Accept: text/plain\nX-Test: foo"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void serverErrorReturnsContent() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/error500"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.metadata()).containsEntry("statusCode", 500);
        assertThat(r.content()).contains("internal error");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("url");
        assertThat(schema.properties()).containsKey("url");
        assertThat(schema.properties()).containsKey("headers");
    }

    @Test
    void toolAnnotation() {
        var ann = WebFetchTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("web_fetch");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.INFORMATION);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.READ_ONLY);
    }
}
