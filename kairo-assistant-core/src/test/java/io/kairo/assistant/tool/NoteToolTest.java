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
    void searchFindsNotes() {
        tool.execute(Map.of("action", "save", "content", "Kairo framework design"), ctx).block();
        ToolResult r = tool.execute(Map.of("action", "search", "query", "kairo"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Kairo framework design");
    }

    @Test
    void deleteNote() {
        tool.execute(Map.of("action", "save", "content", "temp"), ctx).block();
        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        String id = extractId(list.content());

        ToolResult del = tool.execute(Map.of("action", "delete", "id", id), ctx).block();
        assertThat(del.isError()).isFalse();
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noStore).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void saveRequiresContent() {
        ToolResult r = tool.execute(Map.of("action", "save"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    private String extractId(String content) {
        int start = content.indexOf('[') + 1;
        int end = content.indexOf(']');
        return content.substring(start, end);
    }
}
