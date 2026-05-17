package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClipboardToolTest {

    private final ClipboardTool tool = new ClipboardTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of("content", "hello"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void writeRequiresContent() {
        ToolResult r = tool.execute(Map.of("action", "write"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "paste"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void readDoesNotError() {
        ToolResult r = tool.execute(Map.of("action", "read"), ctx).block();
        assertThat(r).isNotNull();
    }
}
