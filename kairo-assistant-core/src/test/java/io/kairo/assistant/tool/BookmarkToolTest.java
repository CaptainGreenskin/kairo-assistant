package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.memory.InMemoryStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BookmarkToolTest {

    private final InMemoryStore store = new InMemoryStore();
    private final BookmarkTool tool = new BookmarkTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("memoryStore", store));

    @Test
    void saveAndList() {
        ToolResult save = tool.execute(
                Map.of("action", "save", "url", "https://kairo.io", "title", "Kairo"), ctx).block();
        assertThat(save.isError()).isFalse();
        assertThat(save.content()).contains("Kairo");

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("kairo.io");
    }

    @Test
    void saveRequiresUrl() {
        ToolResult r = tool.execute(Map.of("action", "save"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void searchBookmarks() {
        tool.execute(Map.of("action", "save", "url", "https://github.com", "title", "GitHub"), ctx).block();
        ToolResult r = tool.execute(Map.of("action", "search", "query", "github"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("GitHub");
    }

    @Test
    void deleteBookmark() {
        tool.execute(Map.of("action", "save", "url", "https://temp.io", "title", "Temp"), ctx).block();
        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();

        int start = list.content().indexOf('[') + 1;
        int end = list.content().indexOf(']');
        String id = list.content().substring(start, end);

        ToolResult del = tool.execute(Map.of("action", "delete", "id", id), ctx).block();
        assertThat(del.isError()).isFalse();
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noStore).block();
        assertThat(r.isError()).isTrue();
    }
}
