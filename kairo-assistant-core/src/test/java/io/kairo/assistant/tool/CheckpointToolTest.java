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
    void emptyCheckpoints() {
        ToolResult r = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(r.content()).contains("No checkpoints");
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noStore).block();
        assertThat(r.isError()).isTrue();
    }
}
