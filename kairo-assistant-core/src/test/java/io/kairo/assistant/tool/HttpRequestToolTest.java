package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpRequestToolTest {

    private final HttpRequestTool tool = new HttpRequestTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void urlRequired() {
        ToolResult r = tool.execute(Map.of("method", "GET"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void invalidUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "not-a-url"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void blankUrlErrors() {
        ToolResult r = tool.execute(Map.of("url", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.inputSchema().properties()).containsKey("url");
        assertThat(tool.inputSchema().properties()).containsKey("method");
        assertThat(tool.inputSchema().properties()).containsKey("headers");
    }
}
