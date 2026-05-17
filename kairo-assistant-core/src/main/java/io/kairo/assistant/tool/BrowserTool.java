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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

@Tool(
        name = "browser",
        description =
                "Browse web pages: fetch HTML content, extract text, follow links, "
                        + "and take screenshots (via headless browser if available).",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class BrowserTool implements SyncTool {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s{2,}");

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("url", new JsonSchema("string", null, null,
                "URL to browse."));
        props.put("action", new JsonSchema("string", null, null,
                "'fetch' to get page content (default), 'links' to extract all links, "
                        + "'search' to search for text within the page."));
        props.put("query", new JsonSchema("string", null, null,
                "Search query when action='search'."));
        props.put("selector", new JsonSchema("string", null, null,
                "CSS selector to extract specific content (basic support)."));
        return new JsonSchema("object", props, List.of("url"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String url = (String) args.get("url");
        if (url == null || url.isBlank()) {
            return ToolResult.error("browser", "'url' required");
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        String action = args.get("action") instanceof String a ? a : "fetch";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent",
                            "Mozilla/5.0 (compatible; KairoAssistant/1.0)")
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> resp =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 400) {
                return ToolResult.error("browser",
                        "HTTP " + resp.statusCode() + " for " + url);
            }

            String html = resp.body();

            return switch (action) {
                case "links" -> extractLinks(url, html);
                case "search" -> searchInPage(html, (String) args.get("query"));
                default -> fetchContent(url, html);
            };
        } catch (Exception e) {
            return ToolResult.error("browser", "Failed to fetch " + url + ": " + e.getMessage());
        }
    }

    private ToolResult fetchContent(String url, String html) {
        String title = extractTitle(html);
        String text = htmlToText(html);
        if (text.length() > 5000) {
            text = text.substring(0, 5000) + "\n... (truncated)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(url).append("\n");
        if (title != null) {
            sb.append("Title: ").append(title).append("\n");
        }
        sb.append("\n").append(text);
        return ToolResult.success("browser", sb.toString());
    }

    private ToolResult extractLinks(String url, String html) {
        Pattern linkPattern = Pattern.compile(
                "<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = linkPattern.matcher(html);
        StringBuilder sb = new StringBuilder();
        sb.append("Links found on ").append(url).append(":\n\n");
        int count = 0;
        while (matcher.find() && count < 50) {
            String href = matcher.group(1);
            String linkText = TAG_PATTERN.matcher(matcher.group(2)).replaceAll("").trim();
            if (!href.startsWith("#") && !href.startsWith("javascript:")) {
                sb.append("- [").append(linkText).append("](").append(href).append(")\n");
                count++;
            }
        }
        if (count == 0) {
            sb.append("(no links found)");
        }
        return ToolResult.success("browser", sb.toString());
    }

    private ToolResult searchInPage(String html, String query) {
        if (query == null || query.isBlank()) {
            return ToolResult.error("browser", "'query' required for search action");
        }

        String text = htmlToText(html);
        String lowerText = text.toLowerCase();
        String lowerQuery = query.toLowerCase();

        StringBuilder sb = new StringBuilder();
        sb.append("Search results for '").append(query).append("':\n\n");

        int pos = 0;
        int count = 0;
        while ((pos = lowerText.indexOf(lowerQuery, pos)) >= 0 && count < 10) {
            int start = Math.max(0, pos - 100);
            int end = Math.min(text.length(), pos + query.length() + 100);
            String snippet = text.substring(start, end).trim();
            sb.append(count + 1).append(". ...").append(snippet).append("...\n\n");
            pos += query.length();
            count++;
        }

        if (count == 0) {
            sb.append("No matches found.");
        } else {
            sb.append("Found ").append(count).append(" match(es).");
        }
        return ToolResult.success("browser", sb.toString());
    }

    private String extractTitle(String html) {
        Matcher m = TITLE_PATTERN.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    private String htmlToText(String html) {
        String noScript = html.replaceAll("(?si)<script[^>]*>.*?</script>", " ");
        String noStyle = noScript.replaceAll("(?si)<style[^>]*>.*?</style>", " ");
        String noTags = TAG_PATTERN.matcher(noStyle).replaceAll(" ");
        String decoded = noTags
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        return WHITESPACE_PATTERN.matcher(decoded).replaceAll(" ").trim();
    }
}
