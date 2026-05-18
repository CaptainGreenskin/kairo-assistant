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
    void listRespectsLimit() {
        ToolResult r = tool.execute(Map.of("action", "list", "limit", 3), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("PID=");
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
        assertThat(r.content()).contains("'query' required");
    }

    @Test
    void searchNoMatchReturnsMessage() {
        ToolResult r = tool.execute(
                Map.of("action", "search", "query", "xyz_nonexistent_process_12345"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("No processes match");
    }

    @Test
    void infoCurrentProcess() {
        long pid = ProcessHandle.current().pid();
        ToolResult r = tool.execute(Map.of("action", "info", "pid", pid), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("PID: " + pid);
        assertThat(r.content()).contains("Alive: true");
    }

    @Test
    void infoNonExistentPidErrors() {
        ToolResult r = tool.execute(Map.of("action", "info", "pid", 999999999), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("PID not found");
    }

    @Test
    void infoRequiresPid() {
        ToolResult r = tool.execute(Map.of("action", "info"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("'pid' required");
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "kill"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void defaultActionIsList() {
        ToolResult r = tool.execute(Map.of(), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("processes");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).isEmpty();
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("query");
        assertThat(schema.properties()).containsKey("pid");
        assertThat(schema.properties()).containsKey("limit");
    }

    @Test
    void toolAnnotation() {
        var ann = ProcessTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("process");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.INFORMATION);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.READ_ONLY);
    }
}
