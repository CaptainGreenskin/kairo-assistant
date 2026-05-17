package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "web_fetch",
        description = "Fetch the content of a URL and return it as text. Useful for reading web pages, APIs, etc.",
        category = ToolCategory.INFORMATION,
        timeoutSeconds = 30,
        sideEffect = ToolSideEffect.READ_ONLY)
public class WebFetchTool implements SyncTool {

    private static final int MAX_BODY_CHARS = 50_000;
    private final HttpClient httpClient;

    public WebFetchTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    WebFetchTool(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("url", new JsonSchema("string", null, null, "URL to fetch."));
        props.put("headers", new JsonSchema("string", null, null, "Optional headers as 'Key: Value' lines."));
        return new JsonSchema("object", props, List.of("url"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String url = (String) args.get("url");
        if (url == null || url.isBlank()) {
            return ToolResult.error("web_fetch", "Parameter 'url' is required");
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET();

            String headers = (String) args.get("headers");
            if (headers != null) {
                for (String line : headers.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        reqBuilder.header(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                    }
                }
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            boolean truncated = false;
            if (body.length() > MAX_BODY_CHARS) {
                body = body.substring(0, MAX_BODY_CHARS) + "\n... (truncated at " + MAX_BODY_CHARS + " chars)";
                truncated = true;
            }

            return ToolResult.success("web_fetch", body,
                    Map.of("statusCode", resp.statusCode(), "truncated", truncated,
                            "contentType", resp.headers().firstValue("content-type").orElse("unknown")));
        } catch (Exception e) {
            return ToolResult.error("web_fetch", "Fetch failed: " + e.getMessage());
        }
    }
}
