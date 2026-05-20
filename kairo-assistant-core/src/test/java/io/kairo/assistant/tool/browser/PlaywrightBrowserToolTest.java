package io.kairo.assistant.tool.browser;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaywrightBrowserToolTest {

    private final PlaywrightBrowserTool tool = new PlaywrightBrowserTool();

    @Test
    void missingActionReturnsError() {
        ToolResult result = tool.execute(Map.of(), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'action' required");
    }

    @Test
    void unknownActionReturnsError() {
        ToolResult result = tool.execute(Map.of("action", "fly"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown action");
    }

    @Test
    void navigateRequiresUrl() {
        ToolResult result = tool.execute(Map.of("action", "navigate"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'url' required");
    }

    @Test
    void clickRequiresSelector() {
        ToolResult result = tool.execute(Map.of("action", "click"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'selector' required");
    }

    @Test
    void typeRequiresSelector() {
        ToolResult result = tool.execute(Map.of("action", "type", "text", "hi"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'selector' required");
    }

    @Test
    void typeRequiresText() {
        ToolResult result = tool.execute(
                Map.of("action", "type", "selector", "#input"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'text' required");
    }

    @Test
    void executeJsRequiresText() {
        ToolResult result = tool.execute(Map.of("action", "execute_js"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'text' required");
    }

    @Test
    void setCookieRequiresNameAndValue() {
        ToolResult result = tool.execute(Map.of("action", "set_cookie"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'cookie_name'");
    }

    @Test
    void closeOnNonExistentSessionIsIdempotent() {
        ToolResult result = tool.execute(Map.of("action", "close"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("closed");
    }

    @Test
    void inputSchemaHasRequiredFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("action");
        assertThat(schema.properties()).containsKey("url");
        assertThat(schema.properties()).containsKey("selector");
        assertThat(schema.properties()).containsKey("text");
    }

    private ToolContext ctx() {
        return new ToolContext("agent-1", "test-session", Map.of());
    }
}
