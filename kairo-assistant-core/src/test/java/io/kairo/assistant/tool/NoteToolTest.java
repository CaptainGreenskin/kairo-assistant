package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.memory.InMemoryStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoteToolTest {

    private final InMemoryStore store = new InMemoryStore();
    private final NoteTool tool = new NoteTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("memoryStore", store));

    @Test
    void saveAndList() {
        ToolResult save = tool.execute(Map.of("action", "save", "content", "Meeting at 3pm"), ctx).block();
        assertThat(save).isNotNull();
        assertThat(save.isError()).isFalse();

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("Meeting at 3pm");
    }

    @Test
    void saveWithTags() {
        ToolResult r = tool.execute(
                Map.of("action", "save", "content", "Tagged note", "tags", "work,urgent"), ctx).block();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void saveWithoutTagsGetsDefaultTag() {
        tool.execute(Map.of("action", "save", "content", "No tags"), ctx).block();
        ToolResult search = tool.execute(Map.of("action", "search", "query", "No tags"), ctx).block();
        assertThat(search.content()).contains("note");
    }

    @Test
    void searchFindsNotes() {
        tool.execute(Map.of("action", "save", "content", "Kairo framework design"), ctx).block();
        ToolResult r = tool.execute(Map.of("action", "search", "query", "kairo"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Kairo framework design");
    }

    @Test
    void searchShowsTags() {
        tool.execute(Map.of("action", "save", "content", "Important item", "tags", "critical"), ctx).block();
        ToolResult r = tool.execute(Map.of("action", "search", "query", "Important"), ctx).block();
        assertThat(r.content()).contains("tags:");
    }

    @Test
    void deleteNote() {
        tool.execute(Map.of("action", "save", "content", "temp"), ctx).block();
        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        String id = extractId(list.content());

        ToolResult del = tool.execute(Map.of("action", "delete", "id", id), ctx).block();
        assertThat(del.isError()).isFalse();
        assertThat(del.content()).contains("Deleted");
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noStore).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("MemoryStore not available");
    }

    @Test
    void emptyDepsErrors() {
        ToolContext emptyDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of("action", "list"), emptyDeps).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void saveRequiresContent() {
        ToolResult r = tool.execute(Map.of("action", "save"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("content");
    }

    @Test
    void blankContentErrors() {
        ToolResult r = tool.execute(Map.of("action", "save", "content", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "archive"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void searchRequiresQuery() {
        ToolResult r = tool.execute(Map.of("action", "search"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("query");
    }

    @Test
    void searchBlankQueryErrors() {
        ToolResult r = tool.execute(Map.of("action", "search", "query", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void deleteRequiresId() {
        ToolResult r = tool.execute(Map.of("action", "delete"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("id");
    }

    @Test
    void deleteBlankIdErrors() {
        ToolResult r = tool.execute(Map.of("action", "delete", "id", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void emptyListMessage() {
        ToolResult r = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(r.content()).contains("No notes");
    }

    @Test
    void searchNoResults() {
        ToolResult r = tool.execute(Map.of("action", "search", "query", "nonexistent_xyz"), ctx).block();
        assertThat(r.content()).contains("No notes found");
    }

    @Test
    void multipleNotesListCount() {
        tool.execute(Map.of("action", "save", "content", "Note A"), ctx).block();
        tool.execute(Map.of("action", "save", "content", "Note B"), ctx).block();
        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("2 note(s)");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("action");
        assertThat(schema.properties()).containsKey("content");
        assertThat(schema.properties()).containsKey("query");
        assertThat(schema.properties()).containsKey("tags");
        assertThat(schema.properties()).containsKey("id");
    }

    @Test
    void toolAnnotation() {
        var ann = NoteTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("note");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.GENERAL);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }

    private String extractId(String content) {
        int start = content.indexOf('[') + 1;
        int end = content.indexOf(']');
        return content.substring(start, end);
    }
}
