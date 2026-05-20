package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.ConversationStore;
import io.kairo.api.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationControllerTest {

    @TempDir
    Path tempDir;

    private ConversationController controller;
    private ConversationStore store;

    @BeforeEach
    void setUp() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .modelProvider("anthropic")
                .modelName("claude-test")
                .dataDir(tempDir.toString())
                .build();

        var toolRegistry = new DefaultToolRegistry();
        var skillRegistry = AssistantSkills.createRegistry();
        AssistantSession session = new AssistantSession(
                new TestFixtures.StubAgent(), toolRegistry,
                new TestFixtures.StubToolExecutor(),
                new InMemoryStore(), new TestFixtures.StubCronScheduler(),
                skillRegistry,
                TestFixtures.stubPluginManager(),
                config);

        controller = new ConversationController(session);
        store = new ConversationStore(tempDir.resolve("conversations").resolve("web"));
    }

    @Test
    void listEmptyReturnsZero() {
        var result = controller.list();
        assertEquals(0, result.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listShowsConversations() {
        store.startSession();
        store.appendMessage("user", "hello");

        var result = controller.list();
        int total = (int) result.get("total");
        assertTrue(total >= 1);
        var conversations = (java.util.List<?>) result.get("conversations");
        assertNotNull(conversations);
        assertFalse(conversations.isEmpty());
    }

    @Test
    void getNonExistentReturnsError() {
        var result = controller.get("no-such-id");
        assertNotNull(result.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getExistingConversation() {
        String sessionId = store.startSession();
        store.appendMessage("user", "test message");
        store.appendMessage("assistant", "test reply");

        var result = controller.get(sessionId);
        assertNull(result.get("error"));
        assertEquals(sessionId, result.get("sessionId"));
        assertEquals(2, result.get("messageCount"));
        var messages = (java.util.List<Map<String, Object>>) result.get("messages");
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("assistant", messages.get(1).get("role"));
    }

    @Test
    void searchRequiresQuery() {
        var result = controller.search("  ", 10, "grouped");
        assertNotNull(result.get("error"));
    }

    @Test
    void searchFindsMessages() {
        store.startSession();
        store.appendMessage("user", "find this specific text");

        var result = controller.search("specific text", 10, "flat");
        int total = (int) result.get("total");
        assertTrue(total >= 1);
    }

    @Test
    void searchNoResults() {
        store.startSession();
        store.appendMessage("user", "hello");

        var result = controller.search("zzznomatchzzz", 10, "flat");
        assertEquals(0, result.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchGroupedMode() {
        store.startSession();
        store.appendMessage("user", "kubernetes deployment");

        var result = controller.search("kubernetes", 10, "grouped");
        assertNotNull(result.get("sessionCount"));
        var sessions = (java.util.List<Map<String, Object>>) result.get("sessions");
        assertFalse(sessions.isEmpty());
        assertNotNull(sessions.get(0).get("sessionId"));
    }

    @Test
    void exportMarkdown() {
        String sessionId = store.startSession();
        store.appendMessage("user", "hello");

        var response = controller.export(sessionId, "markdown");
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("hello"));
    }

    @Test
    void exportJson() {
        String sessionId = store.startSession();
        store.appendMessage("user", "hi");

        var response = controller.export(sessionId, "json");
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("hi"));
    }

    @Test
    void exportNotFound() {
        var response = controller.export("nonexistent", "markdown");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void setTitleSuccess() {
        String sessionId = store.startSession();
        store.appendMessage("user", "x");

        var result = controller.setTitle(sessionId, Map.of("title", "My Chat"));
        assertEquals("updated", result.get("status"));
        assertEquals("My Chat", result.get("title"));
    }

    @Test
    void setTitleBlankFails() {
        var result = controller.setTitle("id", Map.of("title", "  "));
        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("title"));
    }

    @Test
    void setTitleMissingFails() {
        var result = controller.setTitle("id", Map.of());
        assertNotNull(result.get("error"));
    }

    @Test
    void deleteSuccess() {
        String sessionId = store.startSession();
        store.appendMessage("user", "to delete");

        var result = controller.delete(sessionId);
        assertEquals("deleted", result.get("status"));
    }

    @Test
    void deleteNotFound() {
        var result = controller.delete("no-such");
        assertNotNull(result.get("error"));
    }

    @Test
    void getConversationWithTitle() {
        String sessionId = store.startSession();
        store.appendMessage("user", "titled conversation");
        store.setTitle(sessionId, "Test Title");

        var result = controller.get(sessionId);
        assertEquals("Test Title", result.get("title"));
    }
}
