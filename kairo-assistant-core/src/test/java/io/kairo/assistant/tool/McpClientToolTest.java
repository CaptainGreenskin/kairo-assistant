package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpClientToolTest {

    private final McpClientTool tool = new McpClientTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void actionAndUrlRequired() {
        ToolResult r = tool.execute(Map.of("action", "list_tools"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void callRequiresToolName() {
        ToolResult r = tool.execute(
                Map.of("action", "call", "server_url", "http://localhost:9999"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(
                Map.of("action", "deploy", "server_url", "http://localhost:9999"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema().properties()).containsKey("server_url");
    }
}
