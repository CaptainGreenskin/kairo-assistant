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
        name = "http_request",
        description =
                "Make HTTP requests. Supports GET, POST, PUT, DELETE with custom headers and body. "
                        + "Returns status code, headers, and body.",
        category = ToolCategory.EXTERNAL,
        sideEffect = ToolSideEffect.WRITE)
public class HttpRequestTool implements SyncTool {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("url", new JsonSchema("string", null, null, "Request URL."));
        props.put("method", new JsonSchema("string", null, null,
                "HTTP method: GET, POST, PUT, DELETE. Default GET."));
        props.put("headers", new JsonSchema("string", null, null,
                "Headers as key:value pairs, one per line."));
        props.put("body", new JsonSchema("string", null, null,
                "Request body (for POST/PUT)."));
        props.put("timeout", new JsonSchema("integer", null, null,
                "Timeout in seconds. Default 30."));
        return new JsonSchema("object", props, List.of("url"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String url = (String) args.get("url");
        if (url == null || url.isBlank()) {
            return ToolResult.error("http_request", "'url' required");
        }

        String method = (String) args.getOrDefault("method", "GET");
        String headersStr = (String) args.get("headers");
        String body = (String) args.get("body");
        int timeout = 30;
        if (args.get("timeout") instanceof Number n) timeout = n.intValue();

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout));

            if (headersStr != null) {
                for (String line : headersStr.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        reqBuilder.header(line.substring(0, colon).trim(),
                                line.substring(colon + 1).trim());
                    }
                }
            }

            HttpRequest.BodyPublisher bodyPublisher = body != null
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody();

            reqBuilder.method(method.toUpperCase(), bodyPublisher);

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            StringBuilder result = new StringBuilder();
            result.append("HTTP ").append(resp.statusCode()).append("\n");
            resp.headers().map().forEach((k, v) ->
                    result.append(k).append(": ").append(String.join(", ", v)).append("\n"));
            result.append("\n");

            result.append(ToolLimits.truncate(resp.body()));

            return ToolResult.success("http_request", result.toString(),
                    Map.of("statusCode", resp.statusCode()));
        } catch (Exception e) {
            return ToolResult.error("http_request", "Request failed: " + e.getMessage());
        }
    }
}
