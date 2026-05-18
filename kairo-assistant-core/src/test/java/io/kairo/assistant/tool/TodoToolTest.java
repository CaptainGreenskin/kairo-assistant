package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.memory.InMemoryStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TodoToolTest {

    private final InMemoryStore store = new InMemoryStore();
    private final TodoTool tool = new TodoTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("memoryStore", store));

    @Test
    void emptyListShowsNone() {
        ToolResult r = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.content()).contains("No todos");
    }

    @Test
    void addAndList() {
        ToolResult add = tool.execute(Map.of("action", "add", "text", "Buy milk"), ctx).block();
        assertThat(add).isNotNull();
        assertThat(add.content()).contains("Buy milk");

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("Buy milk").contains("[ ]");
    }

    @Test
    void completeMarksItem() {
        tool.execute(Map.of("action", "add", "text", "Task A"), ctx).block();
        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        String id = extractId(list.content());

        ToolResult complete = tool.execute(Map.of("action", "complete", "id", id), ctx).block();
        assertThat(complete.content()).contains("Completed");

        ToolResult after = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(after.content()).contains("[x]");
    }

    @Test
    void deleteRemovesItem() {
        tool.execute(Map.of("action", "add", "text", "Temp"), ctx).block();
        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        String id = extractId(list.content());

        ToolResult del = tool.execute(Map.of("action", "delete", "id", id), ctx).block();
        assertThat(del.content()).contains("Deleted");

        ToolResult after = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(after.content()).contains("No todos");
    }

    @Test
    void addRequiresText() {
        ToolResult r = tool.execute(Map.of("action", "add"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("text");
    }

    @Test
    void addBlankTextErrors() {
        ToolResult r = tool.execute(Map.of("action", "add", "text", "   "), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("text");
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "purge"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void completeNonexistentIdErrors() {
        ToolResult r = tool.execute(Map.of("action", "complete", "id", "zzz999"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("not found");
    }

    @Test
    void deleteNonexistentIdErrors() {
        ToolResult r = tool.execute(Map.of("action", "delete", "id", "zzz999"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("not found");
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noStore).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("MemoryStore not available");
    }

    @Test
    void completeMissingIdErrors() {
        ToolResult r = tool.execute(Map.of("action", "complete"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("id");
    }

    @Test
    void deleteMissingIdErrors() {
        ToolResult r = tool.execute(Map.of("action", "delete"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("id");
    }

    @Test
    void multipleItemsListedInOrder() {
        tool.execute(Map.of("action", "add", "text", "First"), ctx).block();
        tool.execute(Map.of("action", "add", "text", "Second"), ctx).block();
        tool.execute(Map.of("action", "add", "text", "Third"), ctx).block();

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("3 todo(s)");
        assertThat(list.content()).contains("First").contains("Second").contains("Third");
    }

    @Test
    void addedItemHasTodoPrefix() {
        ToolResult add = tool.execute(Map.of("action", "add", "text", "Check"), ctx).block();
        assertThat(add.content()).contains("todo:");
    }

    @Test
    void defaultActionIsListWhenMissing() {
        ToolResult r = tool.execute(Map.of(), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("No todos");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("action");
        assertThat(schema.properties()).containsKey("text");
        assertThat(schema.properties()).containsKey("id");
    }

    @Test
    void toolAnnotation() {
        var ann = TodoTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("todo");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.GENERAL);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }

    @Test
    void emptyDependenciesMapErrors() {
        ToolContext emptyDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of("action", "list"), emptyDeps).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("MemoryStore not available");
    }

    private String extractId(String content) {
        int start = content.indexOf('[', content.indexOf(']') + 1) + 1;
        int end = content.indexOf(']', start);
        return content.substring(start, end);
    }
}
