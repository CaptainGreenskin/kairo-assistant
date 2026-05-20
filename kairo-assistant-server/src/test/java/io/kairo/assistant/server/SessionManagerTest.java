package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.api.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManagerTest {

    @TempDir
    Path tempDir;

    private SessionManager manager;

    @BeforeEach
    void setUp() {
        var config = AssistantConfig.builder()
                .apiKey("test")
                .dataDir(tempDir.toString())
                .build();
        var toolRegistry = new DefaultToolRegistry();
        var skillRegistry = AssistantSkills.createRegistry();
        var session = new AssistantSession(
                new TestFixtures.StubAgent(), toolRegistry,
                new TestFixtures.StubToolExecutor(), new InMemoryStore(),
                new TestFixtures.StubCronScheduler(), skillRegistry,
                TestFixtures.stubPluginManager(),
                config);
        manager = new SessionManager(session);
    }

    @Test
    void getOrCreateCreatesNewSession() {
        var clientSession = manager.getOrCreate("client-1");
        assertNotNull(clientSession);
        assertEquals("client-1", clientSession.clientId());
        assertNotNull(clientSession.conversationStore());
    }

    @Test
    void getOrCreateReturnsSameSession() {
        var first = manager.getOrCreate("client-1");
        var second = manager.getOrCreate("client-1");
        assertSame(first, second);
    }

    @Test
    void getReturnsNullForUnknown() {
        assertNull(manager.get("nonexistent"));
    }

    @Test
    void getReturnsExistingSession() {
        manager.getOrCreate("client-1");
        var session = manager.get("client-1");
        assertNotNull(session);
        assertEquals("client-1", session.clientId());
    }

    @Test
    void removeDeletesSession() {
        manager.getOrCreate("client-1");
        manager.remove("client-1");
        assertNull(manager.get("client-1"));
    }

    @Test
    void allReturnsAllSessions() {
        manager.getOrCreate("client-1");
        manager.getOrCreate("client-2");
        var all = manager.all();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("client-1"));
        assertTrue(all.containsKey("client-2"));
    }

    @Test
    void allReturnsUnmodifiableCopy() {
        manager.getOrCreate("client-1");
        var all = manager.all();
        assertThrows(UnsupportedOperationException.class,
                () -> all.put("x", null));
    }

    @Test
    void multipleClientsAreIndependent() {
        var s1 = manager.getOrCreate("client-1");
        var s2 = manager.getOrCreate("client-2");
        assertNotSame(s1, s2);
        assertNotEquals(s1.clientId(), s2.clientId());
    }

    @Test
    void activeCountReflectsState() {
        assertEquals(0, manager.activeCount());
        manager.getOrCreate("client-1");
        assertEquals(1, manager.activeCount());
        manager.getOrCreate("client-2");
        assertEquals(2, manager.activeCount());
        manager.remove("client-1");
        assertEquals(1, manager.activeCount());
    }

    @Test
    void activeSummaryReturnsCorrectData() {
        var cs = manager.getOrCreate("client-1");
        cs.incrementMessages();
        cs.incrementMessages();

        var summary = manager.activeSummary();
        assertEquals(1, summary.size());
        var entry = summary.get(0);
        assertEquals("client-1", entry.get("clientId"));
        assertEquals(2, entry.get("messageCount"));
        assertNotNull(entry.get("connectedAt"));
    }

    @Test
    void clientSessionMessageCounterIncrements() {
        var cs = manager.getOrCreate("client-1");
        assertEquals(0, cs.messageCount());
        assertEquals(1, cs.incrementMessages());
        assertEquals(2, cs.incrementMessages());
        assertEquals(2, cs.messageCount());
    }

    @Test
    void clientSessionHasConnectedAt() {
        var cs = manager.getOrCreate("client-1");
        assertNotNull(cs.connectedAt());
    }

    @Test
    void removeNonExistentIsNoOp() {
        assertDoesNotThrow(() -> manager.remove("nonexistent"));
        assertEquals(0, manager.activeCount());
    }
}
