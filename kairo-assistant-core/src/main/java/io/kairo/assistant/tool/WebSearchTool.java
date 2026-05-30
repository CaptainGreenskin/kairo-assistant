package io.kairo.assistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Web search via a pluggable, key-based backend (Tavily or Brave). Unlike
 * {@code web_fetch} (which reads one known URL), this discovers relevant pages
 * for a query. The backend is chosen by the {@code WEB_SEARCH_PROVIDER} env var
 * ({@code tavily} | {@code brave}); the matching API key comes from
 * {@code TAVILY_API_KEY} / {@code BRAVE_API_KEY}. When nothing is configured the
 * tool returns a clear, actionable error instead of scraping HTML.
 */
@Tool(
        name = "web_search",
        description =
                "Search the web for a query and return ranked results (title, url, snippet). "
                        + "Use this to discover relevant pages; use web_fetch to read a specific URL.",
        category = ToolCategory.INFORMATION,
        timeoutSeconds = 30,
        sideEffect = ToolSideEffect.READ_ONLY)
public class WebSearchTool implements SyncTool {

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int CAP_MAX_RESULTS = 10;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Config config;

    /** Resolved backend configuration; package-private for testing. */
    record Config(String provider, String apiKey) {}

    public WebSearchTool() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), resolveConfig());
    }

    WebSearchTool(HttpClient httpClient, Config config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    private static Config resolveConfig() {
        String provider = envOrNull("WEB_SEARCH_PROVIDER");
        if (provider != null) provider = provider.trim().toLowerCase();
        String key = switch (provider == null ? "" : provider) {
            case "tavily" -> envOrNull("TAVILY_API_KEY");
            case "brave" -> envOrNull("BRAVE_API_KEY");
            default -> null;
        };
        return new Config(provider, key);
    }

    private static String envOrNull(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("query", new JsonSchema("string", null, null, "Search query."));
        props.put("max_results", new JsonSchema("integer", null, null,
                "Max results to return (1-" + CAP_MAX_RESULTS + ", default " + DEFAULT_MAX_RESULTS + ")."));
        return new JsonSchema("object", props, List.of("query"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String query = args.get("query") instanceof String s ? s.trim() : "";
        if (query.isBlank()) {
            return ToolResult.error("web_search", "Parameter 'query' is required");
        }
        if (config.provider() == null) {
            return ToolResult.error("web_search",
                    "No web search backend configured. Set WEB_SEARCH_PROVIDER=tavily|brave "
                            + "and the matching TAVILY_API_KEY / BRAVE_API_KEY.");
        }
        if (config.apiKey() == null) {
            return ToolResult.error("web_search",
                    "Missing API key for provider '" + config.provider() + "'. Set "
                            + (config.provider().equals("brave") ? "BRAVE_API_KEY" : "TAVILY_API_KEY") + ".");
        }

        int maxResults = DEFAULT_MAX_RESULTS;
        if (args.get("max_results") instanceof Number n) {
            maxResults = Math.max(1, Math.min(CAP_MAX_RESULTS, n.intValue()));
        }

        try {
            List<Result> results = switch (config.provider()) {
                case "tavily" -> searchTavily(query, maxResults);
                case "brave" -> searchBrave(query, maxResults);
                default -> throw new IllegalStateException("Unknown provider: " + config.provider());
            };
            if (results.isEmpty()) {
                return ToolResult.success("web_search", "No results for: " + query,
                        Map.of("provider", config.provider(), "count", 0));
            }
            return ToolResult.success("web_search", format(query, results),
                    Map.of("provider", config.provider(), "count", results.size()));
        } catch (Exception e) {
            return ToolResult.error("web_search", "Search failed: " + e.getMessage());
        }
    }

    private record Result(String title, String url, String snippet) {}

    private List<Result> searchTavily(String query, int maxResults) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "api_key", config.apiKey(),
                "query", query,
                "max_results", maxResults));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Tavily HTTP " + resp.statusCode() + ": " + resp.body());
        }
        List<Result> out = new ArrayList<>();
        JsonNode results = mapper.readTree(resp.body()).path("results");
        for (JsonNode r : results) {
            out.add(new Result(
                    r.path("title").asText(""),
                    r.path("url").asText(""),
                    r.path("content").asText("")));
        }
        return out;
    }

    private List<Result> searchBrave(String query, int maxResults) throws Exception {
        String url = "https://api.search.brave.com/res/v1/web/search?count=" + maxResults
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", config.apiKey())
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Brave HTTP " + resp.statusCode() + ": " + resp.body());
        }
        List<Result> out = new ArrayList<>();
        JsonNode results = mapper.readTree(resp.body()).path("web").path("results");
        for (JsonNode r : results) {
            out.add(new Result(
                    r.path("title").asText(""),
                    r.path("url").asText(""),
                    r.path("description").asText("")));
        }
        return out;
    }

    private String format(String query, List<Result> results) {
        StringBuilder sb = new StringBuilder("Search results for: ").append(query).append("\n\n");
        int i = 1;
        for (Result r : results) {
            sb.append(i++).append(". ").append(r.title()).append("\n");
            sb.append("   ").append(r.url()).append("\n");
            if (!r.snippet().isBlank()) sb.append("   ").append(r.snippet()).append("\n");
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
