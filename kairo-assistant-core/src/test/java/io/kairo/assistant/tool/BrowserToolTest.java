package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrowserToolTest {

    private final BrowserTool tool = new BrowserTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

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
}
