package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebSearchToolTest {

    private final ToolContext ctx = new ToolContext("a", "s", null);

    private WebSearchTool tool(String provider, String apiKey) {
        return new WebSearchTool(HttpClient.newHttpClient(),
                new WebSearchTool.Config(provider, apiKey));
    }

    @Test
    void queryRequired() {
        ToolResult r = tool("tavily", "k").execute(Map.of(), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("query");
    }

    @Test
    void blankQueryErrors() {
        ToolResult r = tool("tavily", "k").execute(Map.of("query", "   "), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("query");
    }

    @Test
    void noProviderConfiguredErrors() {
        ToolResult r = tool(null, null).execute(Map.of("query", "cats"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("WEB_SEARCH_PROVIDER");
    }

    @Test
    void missingApiKeyErrors() {
        ToolResult r = tool("brave", null).execute(Map.of("query", "cats"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("BRAVE_API_KEY");
    }

    @Test
    void schemaRequiresQuery() {
        var schema = tool("tavily", "k").inputSchema();
        assertThat(schema.required()).contains("query");
    }
}
