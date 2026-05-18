package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.memory.InMemoryStore;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionSearchToolTest {

    private final InMemoryStore store = new InMemoryStore();
    private final SessionSearchTool tool = new SessionSearchTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("memoryStore", store));

    @Test
    void searchFindsMatching() {
        store.save(new MemoryEntry("e1", null, "Kairo is great", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();

        ToolResult r = tool.execute(Map.of("query", "kairo"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Kairo is great");
    }

    @Test
    void searchNoResults() {
        ToolResult r = tool.execute(Map.of("query", "nonexistent_xyz"), ctx).block();
        assertThat(r.content()).contains("No results");
    }

    @Test
    void queryRequired() {
        ToolResult r = tool.execute(Map.of("scope", "global"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("query", "test"), noStore).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void blankQueryErrors() {
        ToolResult r = tool.execute(Map.of("query", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void searchWithLimit() {
        for (int i = 0; i < 5; i++) {
            store.save(new MemoryEntry("s" + i, null, "session item " + i, null,
                    MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();
        }
        ToolResult r = tool.execute(Map.of("query", "session item", "limit", 2), ctx).block();
        assertThat(r.content()).contains("Found 2 result");
    }

    @Test
    void resultShowsTags() {
        store.save(new MemoryEntry("tag1", null, "tagged search result", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of("important"), Instant.now(), null)).block();
        ToolResult r = tool.execute(Map.of("query", "tagged search"), ctx).block();
        assertThat(r.content()).contains("tags:");
        assertThat(r.content()).contains("important");
    }

    @Test
    void searchWithScope() {
        store.save(new MemoryEntry("scoped1", null, "scoped search test", null,
                MemoryScope.AGENT, 0.5, null, Set.of(), Instant.now(), null)).block();
        ToolResult r = tool.execute(Map.of("query", "scoped search", "scope", "agent"), ctx).block();
        assertThat(r.isError()).isFalse();
    }
}
