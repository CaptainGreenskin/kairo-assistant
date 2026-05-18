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

class MemorySearchToolTest {

    private final InMemoryStore store = new InMemoryStore();
    private final MemorySearchTool tool = new MemorySearchTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("memoryStore", store));

    @Test
    void searchByKeyword() {
        store.save(new MemoryEntry("m1", null, "Deploy pipeline fixed", null,
                MemoryScope.GLOBAL, 0.8, null, Set.of("ops"), Instant.now(), null)).block();

        ToolResult r = tool.execute(Map.of("keyword", "pipeline", "scope", "global"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("pipeline");
    }

    @Test
    void filterByImportance() {
        store.save(new MemoryEntry("lo", null, "low importance", null,
                MemoryScope.GLOBAL, 0.2, null, Set.of(), Instant.now(), null)).block();
        store.save(new MemoryEntry("hi", null, "high importance", null,
                MemoryScope.GLOBAL, 0.9, null, Set.of(), Instant.now(), null)).block();

        ToolResult r = tool.execute(
                Map.of("keyword", "importance", "scope", "global", "min_importance", 0.5), ctx).block();
        assertThat(r.content()).contains("high importance");
        assertThat(r.content()).doesNotContain("low importance");
    }

    @Test
    void emptyResults() {
        ToolResult r = tool.execute(Map.of("keyword", "nothing_here_xyz"), ctx).block();
        assertThat(r.content()).contains("No memories");
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of(), noStore).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void invalidScopeErrors() {
        ToolResult r = tool.execute(Map.of("keyword", "test", "scope", "invalid_scope"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Invalid scope");
    }

    @Test
    void searchWithTags() {
        store.save(new MemoryEntry("t1", null, "tagged entry", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of("alpha", "beta"), Instant.now(), null)).block();
        store.save(new MemoryEntry("t2", null, "untagged entry", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();

        ToolResult r = tool.execute(Map.of("keyword", "entry", "scope", "global", "tags", "alpha"), ctx).block();
        assertThat(r.content()).contains("tagged entry");
    }

    @Test
    void searchWithLimit() {
        for (int i = 0; i < 5; i++) {
            store.save(new MemoryEntry("lim" + i, null, "limited entry " + i, null,
                    MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();
        }

        ToolResult r = tool.execute(
                Map.of("keyword", "limited entry", "scope", "global", "limit", 2), ctx).block();
        assertThat(r.content()).contains("Found 2 memories");
    }

    @Test
    void searchWithoutScope() {
        store.save(new MemoryEntry("ns1", null, "no scope search test", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();

        ToolResult r = tool.execute(Map.of("keyword", "no scope search"), ctx).block();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void resultShowsImportance() {
        store.save(new MemoryEntry("imp1", null, "importance display test", null,
                MemoryScope.GLOBAL, 0.8, null, Set.of(), Instant.now(), null)).block();

        ToolResult r = tool.execute(Map.of("keyword", "importance display", "scope", "global"), ctx).block();
        assertThat(r.content()).contains("importance=0.8");
    }

    @Test
    void resultShowsTags() {
        store.save(new MemoryEntry("tag1", null, "tag display test", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of("myTag"), Instant.now(), null)).block();

        ToolResult r = tool.execute(Map.of("keyword", "tag display", "scope", "global"), ctx).block();
        assertThat(r.content()).contains("#myTag");
    }
}
