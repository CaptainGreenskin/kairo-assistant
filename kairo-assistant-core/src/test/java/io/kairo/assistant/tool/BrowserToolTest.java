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

class BrowserToolTest {

    private final BrowserTool tool = new BrowserTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/page", exchange -> {
            String html = """
                    <html>
                    <head><title>Test Page</title></head>
                    <body>
                    <h1>Welcome</h1>
                    <p>This is a test page with some content.</p>
                    <a href="/other">Link One</a>
                    <a href="/second">Link Two</a>
                    </body>
                    </html>
                    """;
            byte[] body = html.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            byte[] body = "Server Error".getBytes();
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
        ToolResult r = tool.execute(Map.of("action", "fetch"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void blankUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void unreachableUrlReturnsError() {
        ToolResult r = tool.execute(
                Map.of("url", "http://localhost:1", "action", "search"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Failed to fetch");
    }

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema().properties()).containsKey("url");
        assertThat(tool.inputSchema().properties()).containsKey("action");
        assertThat(tool.inputSchema().properties()).containsKey("query");
    }

    @Test
    void fetchExtractsTitleAndContent() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/page"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Test Page");
        assertThat(r.content()).contains("Welcome");
    }

    @Test
    void linksActionExtractsLinks() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/page", "action", "links"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Link One");
        assertThat(r.content()).contains("Link Two");
        assertThat(r.content()).contains("/other");
    }

    @Test
    void searchFindsMatchingText() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/page", "action", "search", "query", "content"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("1 match");
    }

    @Test
    void searchWithNoMatchesReportsNone() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/page", "action", "search", "query", "xyznonexistent"),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("No matches found");
    }

    @Test
    void searchRequiresQuery() {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/page", "action", "search"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("'query' required");
    }

    @Test
    void httpErrorStatusReturnsError() {
        ToolResult r = tool.execute(Map.of("url", baseUrl + "/error"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("500");
    }

    @Test
    void autoHttpsPrefix() {
        ToolResult r = tool.execute(Map.of("url", "localhost:1"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("https://localhost:1");
    }
}
