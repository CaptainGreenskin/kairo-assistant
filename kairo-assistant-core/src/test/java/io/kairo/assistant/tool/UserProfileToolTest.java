package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.memory.InMemoryStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserProfileToolTest {

    private final InMemoryStore store = new InMemoryStore();
    private final UserProfileTool tool = new UserProfileTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("memoryStore", store));

    @Test
    void setAndGet() {
        tool.execute(Map.of("action", "set", "key", "name", "value", "Alice"), ctx).block();
        ToolResult r = tool.execute(Map.of("action", "get", "key", "name"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Alice");
    }

    @Test
    void listPreferences() {
        tool.execute(Map.of("action", "set", "key", "lang", "value", "zh"), ctx).block();
        ToolResult r = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(r.content()).contains("lang").contains("zh");
    }

    @Test
    void deletePreference() {
        tool.execute(Map.of("action", "set", "key", "tz", "value", "UTC+8"), ctx).block();
        ToolResult del = tool.execute(Map.of("action", "delete", "key", "tz"), ctx).block();
        assertThat(del.isError()).isFalse();
    }

    @Test
    void getMissingKey() {
        ToolResult r = tool.execute(Map.of("action", "get", "key", "nonexistent"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("No preference");
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noStore).block();
        assertThat(r.isError()).isTrue();
    }
}
