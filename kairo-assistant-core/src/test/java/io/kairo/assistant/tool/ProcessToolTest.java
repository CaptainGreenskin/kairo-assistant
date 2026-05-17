package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessToolTest {

    private final ProcessTool tool = new ProcessTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void listProcesses() {
        ToolResult r = tool.execute(Map.of("action", "list", "limit", 5), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("processes");
    }

    @Test
    void searchByName() {
        ToolResult r = tool.execute(Map.of("action", "search", "query", "java"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void searchRequiresQuery() {
        ToolResult r = tool.execute(Map.of("action", "search"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void infoCurrentProcess() {
        long pid = ProcessHandle.current().pid();
        ToolResult r = tool.execute(Map.of("action", "info", "pid", pid), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("PID: " + pid);
    }
}
