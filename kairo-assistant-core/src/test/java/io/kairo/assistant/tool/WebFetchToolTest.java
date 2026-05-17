package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebFetchToolTest {

    private final WebFetchTool tool = new WebFetchTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void urlRequired() {
        ToolResult r = tool.execute(Map.of(), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void invalidUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "not-a-url"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema().properties()).containsKey("url");
    }
}
