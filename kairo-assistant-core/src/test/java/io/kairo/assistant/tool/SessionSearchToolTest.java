package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.assistant.agent.ConversationStore;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSearchToolTest {

    @TempDir
    Path tempDir;

    private ConversationStore store;
    private SessionSearchTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        store = new ConversationStore(tempDir);
        tool = new SessionSearchTool();
        ctx = new ToolContext("a", "s", Map.of("conversationStore", store));
    }

    @Test
    void searchFindsMatching() {
        store.startSession();
        store.appendMessage("user", "Kairo is a great framework");

        ToolResult r = tool.execute(Map.of("query", "kairo"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).containsIgnoringCase("kairo");
    }

    @Test
    void searchNoResults() {
        store.startSession();
        store.appendMessage("user", "hello world");

        ToolResult r = tool.execute(Map.of("query", "nonexistent_xyz"), ctx).block();
        assertThat(r.content()).contains("No conversations found");
    }

    @Test
    void queryRequired() {
        ToolResult r = tool.execute(Map.of("limit", 5), ctx).block();
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
    void searchGroupsResultsBySession() {
        store.startSession();
        store.appendMessage("user", "kubernetes question one");
        store.appendMessage("assistant", "kubernetes answer one");

        var store2 = new ConversationStore(tempDir);
        store2.startSession();
        store2.appendMessage("user", "kubernetes question two");

        ToolResult r = tool.execute(Map.of("query", "kubernetes"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("2 session(s)");
    }

    @Test
    void searchWithLimit() {
        for (int i = 0; i < 10; i++) {
            var s = new ConversationStore(tempDir);
            s.startSession();
            s.appendMessage("user", "docker question " + i);
        }

        ToolResult r = tool.execute(Map.of("query", "docker", "limit", 3), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("3 session(s)");
    }

    @Test
    void emptyDependenciesErrors() {
        ToolContext emptyDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of("query", "test"), emptyDeps).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("ConversationStore not available");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("query");
        assertThat(schema.properties()).containsKey("query");
        assertThat(schema.properties()).containsKey("limit");
    }

    @Test
    void toolAnnotation() {
        var ann = SessionSearchTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("session_search");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.INFORMATION);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.READ_ONLY);
    }
}
