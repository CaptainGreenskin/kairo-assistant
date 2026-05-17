package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.ConversationStore;
import io.kairo.assistant.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.assistant.tool.ToolCallLogger;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusControllerTest {

    private StatusController controller;
    private DefaultToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new ToolDefinition(
                "test_tool", "A test tool",
                ToolCategory.GENERAL,
                new JsonSchema("object", Map.of(), List.of(), "Test"),
                null));

        var config = TestFixtures.defaultConfig();
        var skillRegistry = AssistantSkills.createRegistry();
        var session = new AssistantSession(
                new TestFixtures.StubAgent(), toolRegistry,
                new TestFixtures.StubToolExecutor(), new InMemoryStore(),
                new TestFixtures.StubCronScheduler(), skillRegistry,
                new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp")),
                config);

        controller = new StatusController(session, new MetricsCollector(), new SessionManager(session));
    }

    @Test
    void statusReturnsRunning() {
        var result = controller.status();
        assertEquals("running", result.get("status"));
        assertEquals("anthropic", result.get("provider"));
        assertEquals("claude-test", result.get("model"));
        assertTrue((Integer) result.get("toolCount") >= 1);
    }

    @Test
    void systemReturnsInfo() {
        var result = controller.system();
        assertNotNull(result.get("javaVersion"));
        assertNotNull(result.get("os"));
        assertTrue((Integer) result.get("processors") > 0);
        assertTrue((Long) result.get("memoryMaxMB") > 0);
    }

    @Test
    void agentStateReturnsIdle() {
        var result = controller.agentState();
        assertEquals("IDLE", result.get("state"));
        assertEquals("test", result.get("agentId"));
        assertEquals("Test", result.get("agentName"));
    }

    @Test
    void healthReturnsOk() {
        var result = controller.health();
        assertEquals("ok", result.get("status"));
        assertNotNull(result.get("uptime"));
        assertNotNull(result.get("version"));
    }

    @Test
    void healthComponentsIncludeAgent() {
        var result = controller.health();
        @SuppressWarnings("unchecked")
        var components = (Map<String, String>) result.get("components");
        assertEquals("ok", components.get("agent"));
    }

    @Test
    void toolsReturnsRegisteredTools() {
        var tools = controller.tools();
        assertFalse(tools.isEmpty());
        var first = tools.get(0);
        assertNotNull(first.get("name"));
        assertNotNull(first.get("description"));
        assertNotNull(first.get("category"));
    }

    @Test
    void metricsReturnsPrometheus() {
        String metrics = controller.metrics();
        assertNotNull(metrics);
        assertTrue(metrics.contains("kairo_"));
    }

    @Test
    void skillsReturnsList() {
        var skills = controller.skills();
        assertNotNull(skills);
        for (var skill : skills) {
            assertNotNull(skill.get("name"));
            assertNotNull(skill.get("version"));
        }
    }

    @Test
    void detailedHealthIncludesMemory() {
        var result = controller.detailedHealth();
        assertEquals("ok", result.get("status"));
        assertNotNull(result.get("memory"));
        assertNotNull(result.get("runtime"));
        assertNotNull(result.get("components"));
    }

    @Test
    void sessionsReturnsEmptyByDefault() {
        var sessions = controller.sessions();
        assertNotNull(sessions);
    }

    @Test
    void toolHistoryWithoutLoggerReturnsEmpty() {
        var result = controller.toolHistory(10);
        assertNotNull(result);
        assertEquals(0, result.get("totalCalls"));
        assertNotNull(result.get("calls"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolHistoryWithLoggerReturnsCalls() {
        var loggerExecutor = new ToolCallLogger(new TestFixtures.StubToolExecutor());
        loggerExecutor.execute("test_tool", Map.of()).block();
        loggerExecutor.execute("test_tool", Map.of()).block();

        var config = TestFixtures.defaultConfig();
        var skillRegistry = AssistantSkills.createRegistry();
        var loggedSession = new AssistantSession(
                new TestFixtures.StubAgent(), toolRegistry, loggerExecutor,
                new InMemoryStore(), new TestFixtures.StubCronScheduler(),
                skillRegistry,
                new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp")),
                config);

        var loggedController = new StatusController(loggedSession, new MetricsCollector(), new SessionManager(loggedSession));
        var result = loggedController.toolHistory(10);

        assertEquals(2L, result.get("totalCalls"));
        assertEquals(0L, result.get("totalErrors"));
        var calls = (List<Map<String, Object>>) result.get("calls");
        assertEquals(2, calls.size());
        assertEquals("test_tool", calls.get(0).get("tool"));
        assertTrue((Boolean) calls.get(0).get("success"));
    }

    @Test
    void searchSessionsReturnsResults() {
        var result = controller.searchSessions("nonexistent", 10);
        assertNotNull(result);
        assertEquals("nonexistent", result.get("query"));
        assertEquals(0, result.get("total"));
    }

    @Test
    void configReturnsSanitizedValues() {
        var result = controller.config();
        assertNotNull(result);
        assertEquals("anthropic", result.get("modelProvider"));
        assertEquals("claude-test", result.get("modelName"));
        String maskedKey = (String) result.get("apiKey");
        assertTrue(maskedKey.contains("..."), "API key should be masked");
        assertFalse(maskedKey.equals("test-key"), "API key should not be in plaintext");
    }

    @Test
    void analyticsReturnsStats() {
        var result = controller.analytics();
        assertNotNull(result);
        assertNotNull(result.get("uptime"));
        assertNotNull(result.get("totalSessions"));
        assertNotNull(result.get("registeredTools"));
        assertNotNull(result.get("registeredSkills"));
        assertEquals("anthropic", result.get("provider"));
        assertEquals("claude-test", result.get("model"));
    }

    @Test
    void pluginsReturnsEmptyByDefault() {
        var result = controller.plugins();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void summarizeRejectsBlankSessionId() {
        var result = controller.summarize(Map.of("sessionId", "")).block();
        assertNotNull(result);
        assertEquals("sessionId is required", result.get("error"));
    }

    @Test
    void summarizeRejectsNonExistentSession() {
        var result = controller.summarize(Map.of("sessionId", "nonexistent-abc")).block();
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    @Test
    void deleteSessionReturnsNotFoundForMissing() {
        var result = controller.deleteSession("no-such-session");
        assertEquals("session not found", result.get("error"));
    }

    @Test
    void deleteSessionDeletesExistingSession(@TempDir Path tempDir) {
        var ctrl = controllerWithDataDir(tempDir);
        var store = new ConversationStore(tempDir.resolve("conversations").resolve("web"));
        store.startSession();
        String sessionId = store.currentSessionId();
        store.appendMessage("user", "test message");

        var result = ctrl.deleteSession(sessionId);
        assertEquals("deleted", result.get("status"));
        assertEquals(sessionId, result.get("sessionId"));
    }

    @Test
    void exportSessionReturnsContentForExisting(@TempDir Path tempDir) {
        var ctrl = controllerWithDataDir(tempDir);
        var store = new ConversationStore(tempDir.resolve("conversations").resolve("web"));
        store.startSession();
        String sessionId = store.currentSessionId();
        store.appendMessage("user", "hello");
        store.appendMessage("assistant", "hi there");

        var result = ctrl.exportSession(sessionId, "markdown");
        assertEquals(sessionId, result.get("sessionId"));
        assertEquals("markdown", result.get("format"));
        String content = (String) result.get("content");
        assertTrue(content.contains("hello"));
    }

    @Test
    void exportSessionReturnsErrorForMissing() {
        var result = controller.exportSession("nonexistent", "markdown");
        assertEquals("session not found or empty", result.get("error"));
    }

    @Test
    void renameSessionSetsTitle(@TempDir Path tempDir) {
        var ctrl = controllerWithDataDir(tempDir);
        var store = new ConversationStore(tempDir.resolve("conversations").resolve("web"));
        store.startSession();
        String sessionId = store.currentSessionId();
        store.appendMessage("user", "test");

        var result = ctrl.renameSession(sessionId, Map.of("title", "My Chat"));
        assertEquals("ok", result.get("status"));
        assertEquals("My Chat", result.get("title"));
    }

    @Test
    void renameSessionRejectsBlankTitle() {
        var result = controller.renameSession("abc", Map.of("title", "  "));
        assertEquals("title is required", result.get("error"));
    }

    @Test
    void renameSessionRejectsNonExistent() {
        var result = controller.renameSession("no-exist", Map.of("title", "Title"));
        assertEquals("session not found", result.get("error"));
    }

    @Test
    void getSystemPromptReturnsEmptyByDefault(@TempDir Path tempDir) {
        var ctrl = controllerWithDataDir(tempDir);
        var result = ctrl.getSystemPrompt();
        assertEquals("", result.get("content"));
        assertNotNull(result.get("path"));
    }

    @Test
    void updateSystemPromptSavesContent(@TempDir Path tempDir) throws IOException {
        var ctrl = controllerWithDataDir(tempDir);
        var result = ctrl.updateSystemPrompt(Map.of("content", "Be helpful."));
        assertEquals("saved", result.get("status"));

        Path file = tempDir.resolve("custom-instructions.md");
        assertTrue(Files.exists(file));
        assertEquals("Be helpful.", Files.readString(file));
    }

    @Test
    void updateSystemPromptRejectsNullContent() {
        var result = controller.updateSystemPrompt(Map.of());
        assertEquals("content is required", result.get("error"));
    }

    @Test
    void getSystemPromptReadsExistingFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("custom-instructions.md"), "Custom rules here");
        var ctrl = controllerWithDataDir(tempDir);
        var result = ctrl.getSystemPrompt();
        assertEquals("Custom rules here", result.get("content"));
    }

    @Test
    void endpointAnalyticsReturnsEmptyByDefault() {
        var result = controller.endpointAnalytics();
        @SuppressWarnings("unchecked")
        var endpoints = (Map<String, Long>) result.get("endpoints");
        assertNotNull(endpoints);
        assertTrue(endpoints.isEmpty());
    }

    private StatusController controllerWithDataDir(Path dataDir) {
        var config = AssistantConfig.builder()
                .apiKey("test-key")
                .modelProvider("anthropic")
                .modelName("claude-test")
                .dataDir(dataDir.toString())
                .build();
        var skillRegistry = AssistantSkills.createRegistry();
        var session = new AssistantSession(
                new TestFixtures.StubAgent(), toolRegistry,
                new TestFixtures.StubToolExecutor(), new InMemoryStore(),
                new TestFixtures.StubCronScheduler(), skillRegistry,
                new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp")),
                config);
        return new StatusController(session, new MetricsCollector(), new SessionManager(session));
    }
}
