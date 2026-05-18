package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.memory.InMemoryStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        assertThat(r.content()).contains("MemoryStore not available");
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

    @Test
    void timeRangeFilters1h() {
        store.save(new MemoryEntry("recent1", null, "recent data", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();
        store.save(new MemoryEntry("old1", null, "old data", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now().minus(2, ChronoUnit.HOURS), null)).block();

        ToolResult r = tool.execute(
                Map.of("keyword", "data", "scope", "global", "time_range", "1h"), ctx).block();
        assertThat(r.content()).contains("recent data");
        assertThat(r.content()).doesNotContain("old data");
    }

    @Test
    void timeRangeAll() {
        store.save(new MemoryEntry("old2", null, "ancient entry", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now().minus(60, ChronoUnit.DAYS), null)).block();

        ToolResult r = tool.execute(
                Map.of("keyword", "ancient entry", "scope", "global", "time_range", "all"), ctx).block();
        assertThat(r.content()).contains("ancient entry");
    }

    @Test
    void limitClampedToMax100() {
        for (int i = 0; i < 3; i++) {
            store.save(new MemoryEntry("clamp" + i, null, "clamped item " + i, null,
                    MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();
        }

        ToolResult r = tool.execute(
                Map.of("keyword", "clamped item", "scope", "global", "limit", 999), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Found 3 memories");
    }

    @Test
    void limitClampedToMin1() {
        store.save(new MemoryEntry("min1", null, "min limit test", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();

        ToolResult r = tool.execute(
                Map.of("keyword", "min limit test", "scope", "global", "limit", 0), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Found 1 memories");
    }

    @Test
    void searchNoKeywordReturnsAll() {
        store.save(new MemoryEntry("nk1", null, "no keyword needed", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();

        ToolResult r = tool.execute(Map.of("scope", "global"), ctx).block();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void metadataCountInResult() {
        store.save(new MemoryEntry("mc1", null, "meta count one", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();
        store.save(new MemoryEntry("mc2", null, "meta count two", null,
                MemoryScope.GLOBAL, 0.5, null, Set.of(), Instant.now(), null)).block();

        ToolResult r = tool.execute(Map.of("keyword", "meta count", "scope", "global"), ctx).block();
        assertThat(r.metadata()).containsEntry("count", 2);
    }

    @Test
    void emptyDependenciesErrors() {
        ToolContext emptyDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of(), emptyDeps).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("MemoryStore not available");
    }

    @Test
    void scopeSessionValid() {
        store.save(new MemoryEntry("sess1", null, "session scoped item", null,
                MemoryScope.SESSION, 0.5, null, Set.of(), Instant.now(), null)).block();

        ToolResult r = tool.execute(Map.of("keyword", "session scoped", "scope", "session"), ctx).block();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).isEmpty();
        assertThat(schema.properties()).containsKey("keyword");
        assertThat(schema.properties()).containsKey("tags");
        assertThat(schema.properties()).containsKey("scope");
        assertThat(schema.properties()).containsKey("time_range");
        assertThat(schema.properties()).containsKey("min_importance");
        assertThat(schema.properties()).containsKey("limit");
    }

    @Test
    void toolAnnotation() {
        var ann = MemorySearchTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("memory_search");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.INFORMATION);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.READ_ONLY);
    }
}
