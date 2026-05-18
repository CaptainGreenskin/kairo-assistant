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

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of("url", "https://test.com"), ctx).block();
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
    }

    @Test
    void deleteRequiresId() {
        ToolResult r = tool.execute(Map.of("action", "delete"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void emptyListMessage() {
        ToolResult r = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(r.content()).contains("No bookmarks");
    }

    @Test
    void saveWithTags() {
        ToolResult r = tool.execute(
                Map.of("action", "save", "url", "https://react.dev", "title", "React", "tags", "frontend,docs"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("React");
    }

    @Test
    void saveWithoutTitleUsesUrl() {
        ToolResult r = tool.execute(
                Map.of("action", "save", "url", "https://example.com"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("example.com");
    }

    @Test
    void searchNoResults() {
        ToolResult r = tool.execute(Map.of("action", "search", "query", "nonexistent_xyz"), ctx).block();
        assertThat(r.content()).contains("No bookmarks match");
    }

    @Test
    void blankUrlErrors() {
        ToolResult r = tool.execute(Map.of("action", "save", "url", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }
}
