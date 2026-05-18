package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.memory.InMemoryStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectToolTest {

    private final InMemoryStore store = new InMemoryStore();
    private final ProjectTool tool = new ProjectTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("memoryStore", store));

    @Test
    void createAndList() {
        ToolResult create = tool.execute(Map.of("action", "create", "project", "Kairo"), ctx).block();
        assertThat(create.isError()).isFalse();
        assertThat(create.content()).contains("Kairo");

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("Kairo");
    }

    @Test
    void addTaskAndStatus() {
        tool.execute(Map.of("action", "create", "project", "Demo"), ctx).block();
        tool.execute(Map.of("action", "add_task", "project", "Demo", "task", "Write tests"), ctx).block();

        ToolResult status = tool.execute(Map.of("action", "status", "project", "Demo"), ctx).block();
        assertThat(status.content()).contains("Todo: 1");
    }

    @Test
    void updateTaskStatus() {
        tool.execute(Map.of("action", "create", "project", "P1"), ctx).block();
        tool.execute(Map.of("action", "add_task", "project", "P1", "task", "Fix bug"), ctx).block();

        ToolResult update = tool.execute(
                Map.of("action", "update_task", "project", "P1", "task", "Fix bug", "task_status", "done"), ctx).block();
        assertThat(update.isError()).isFalse();

        ToolResult status = tool.execute(Map.of("action", "status", "project", "P1"), ctx).block();
        assertThat(status.content()).contains("Done: 1");
    }

    @Test
    void emptyProjects() {
        ToolResult r = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(r.content()).contains("No projects");
    }

    @Test
    void createRequiresName() {
        ToolResult r = tool.execute(Map.of("action", "create"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noStore).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of(), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "destroy"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void addTaskRequiresProjectAndTask() {
        ToolResult r = tool.execute(Map.of("action", "add_task"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void updateTaskRequiresAllFields() {
        ToolResult r = tool.execute(Map.of("action", "update_task", "project", "P", "task", "T"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void updateNonexistentTaskErrors() {
        ToolResult r = tool.execute(
                Map.of("action", "update_task", "project", "X", "task", "Y", "task_status", "done"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("not found");
    }

    @Test
    void statusRequiresProject() {
        ToolResult r = tool.execute(Map.of("action", "status"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void statusShowsMultipleTasks() {
        tool.execute(Map.of("action", "create", "project", "Multi"), ctx).block();
        tool.execute(Map.of("action", "add_task", "project", "Multi", "task", "T1"), ctx).block();
        tool.execute(Map.of("action", "add_task", "project", "Multi", "task", "T2"), ctx).block();
        tool.execute(Map.of("action", "add_task", "project", "Multi", "task", "T3"), ctx).block();

        ToolResult status = tool.execute(Map.of("action", "status", "project", "Multi"), ctx).block();
        assertThat(status.content()).contains("Total: 3");
    }

    @Test
    void addTaskWithPriority() {
        tool.execute(Map.of("action", "create", "project", "Prio"), ctx).block();
        ToolResult r = tool.execute(
                Map.of("action", "add_task", "project", "Prio", "task", "Urgent", "priority", "high"), ctx).block();
        assertThat(r.content()).contains("high");
    }

    @Test
    void createProjectWithBlankNameErrors() {
        ToolResult r = tool.execute(Map.of("action", "create", "project", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }
}
