package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.memory.InMemoryStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckpointToolTest {

    private final InMemoryStore store = new InMemoryStore();
    private final CheckpointTool tool = new CheckpointTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("memoryStore", store));

    @Test
    void saveAndList() {
        ToolResult save = tool.execute(
                Map.of("action", "save", "label", "v1", "notes", "Initial setup"), ctx).block();
        assertThat(save.isError()).isFalse();
        assertThat(save.content()).contains("v1");

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("v1");
    }

    @Test
    void saveWithDefaultLabel() {
        ToolResult save = tool.execute(Map.of("action", "save"), ctx).block();
        assertThat(save.isError()).isFalse();
        assertThat(save.content()).contains("Checkpoint saved");
    }

    @Test
    void infoReturnsDetails() {
        tool.execute(Map.of("action", "save", "label", "cp1", "notes", "Test checkpoint"), ctx).block();
        ToolResult r = tool.execute(Map.of("action", "info", "label", "cp1"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("CHECKPOINT:cp1").contains("Test checkpoint");
    }

    @Test
    void infoNotFound() {
        ToolResult r = tool.execute(Map.of("action", "info", "label", "missing"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void infoRequiresLabel() {
        ToolResult r = tool.execute(Map.of("action", "info"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("label");
    }

    @Test
    void emptyCheckpoints() {
        InMemoryStore empty = new InMemoryStore();
        ToolContext emptyCtx = new ToolContext("a", "s", Map.of("memoryStore", empty));
        ToolResult r = tool.execute(Map.of("action", "list"), emptyCtx).block();
        assertThat(r.content()).contains("No checkpoints");
    }

    @Test
    void restoreReturnsCheckpointContext() {
        tool.execute(Map.of("action", "save", "label", "r1", "notes", "Before refactoring"), ctx).block();
        ToolResult r = tool.execute(Map.of("action", "restore", "label", "r1"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Restored context").contains("r1").contains("Before refactoring");
    }

    @Test
    void restoreNotFound() {
        ToolResult r = tool.execute(Map.of("action", "restore", "label", "nonexistent"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void restoreMissingLabel() {
        ToolResult r = tool.execute(Map.of("action", "restore"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("label");
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noStore).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("MemoryStore");
    }

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of("label", "test"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("action");
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "rollback"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void schemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("label");
        assertThat(schema.properties()).containsKey("notes");
        assertThat(schema.required()).containsExactly("action");
    }
}
