package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DelegateTaskToolTest {

    private final DelegateTaskTool tool = new DelegateTaskTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void taskRequired() {
        ToolResult r = tool.execute(Map.of("context", "some context"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void noModelProviderErrors() {
        ToolResult r = tool.execute(Map.of("task", "Do something"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("ModelProvider");
    }

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema().properties()).containsKey("task");
    }
}
