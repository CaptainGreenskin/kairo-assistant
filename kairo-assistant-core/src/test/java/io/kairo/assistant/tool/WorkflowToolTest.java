package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowToolTest {

    private final WorkflowTool tool = new WorkflowTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void defineAndList() {
        ToolResult def = tool.execute(
                Map.of("action", "define", "name", "deploy", "steps", "build\ntest\ndeploy"), ctx).block();
        assertThat(def.content()).contains("deploy").contains("3 steps");

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("deploy").contains("3 steps");
    }

    @Test
    void describeShowsSteps() {
        tool.execute(Map.of("action", "define", "name", "ci", "steps", "lint\ntest"), ctx).block();
        ToolResult desc = tool.execute(Map.of("action", "describe", "name", "ci"), ctx).block();
        assertThat(desc.content()).contains("lint").contains("test");
    }

    @Test
    void deleteRemovesWorkflow() {
        tool.execute(Map.of("action", "define", "name", "temp", "steps", "step1"), ctx).block();
        ToolResult del = tool.execute(Map.of("action", "delete", "name", "temp"), ctx).block();
        assertThat(del.content()).contains("Deleted");

        ToolResult desc = tool.execute(Map.of("action", "describe", "name", "temp"), ctx).block();
        assertThat(desc.isError()).isTrue();
    }

    @Test
    void emptyListShowsNone() {
        WorkflowTool fresh = new WorkflowTool();
        ToolResult list = fresh.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("No workflows");
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "run"), ctx).block();
        assertThat(r.isError()).isTrue();
    }
}
