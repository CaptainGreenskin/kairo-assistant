package io.kairo.assistant.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationStoreTest {

    @TempDir
    Path tempDir;

    private ConversationStore store;

    @BeforeEach
    void setUp() {
        store = new ConversationStore(tempDir);
    }

    @Test
    void startSessionCreatesId() {
        String id = store.startSession();
        assertNotNull(id);
        assertFalse(id.isBlank());
    }

    @Test
    void appendAndLoadMessages() {
        store.startSession();
        store.appendMessage("user", "hello");
        store.appendMessage("assistant", "hi there");

        var entries = store.loadSession(store.currentSessionId());
        assertEquals(3, entries.size());
        assertEquals("session_start", entries.get(0).get("type"));
        assertEquals("user", entries.get(1).get("role"));
        assertEquals("hello", entries.get(1).get("content"));
        assertEquals("assistant", entries.get(2).get("role"));
    }

    @Test
    void listSessionsShowsMostRecent() {
        store.startSession();
        store.appendMessage("user", "first session");

        var store2 = new ConversationStore(tempDir);
        store2.startSession();
        store2.appendMessage("user", "second session");

        List<Map<String, String>> sessions = store.listSessions();
        assertEquals(2, sessions.size());
        assertNotNull(sessions.get(0).get("id"));
        assertNotNull(sessions.get(0).get("preview"));
    }

    @Test
    void loadNonExistentSessionReturnsEmpty() {
        var entries = store.loadSession("nonexistent");
        assertTrue(entries.isEmpty());
    }

    @Test
    void previewExtractsFirstUserMessage() {
        store.startSession();
        store.appendMessage("user", "What's the weather?");

        var sessions = store.listSessions();
        assertEquals(1, sessions.size());
        assertTrue(sessions.get(0).get("preview").contains("weather"));
    }

    @Test
    void autoStartsSessionOnAppend() {
        store.appendMessage("user", "hello");
        assertNotNull(store.currentSessionId());
        var entries = store.loadSession(store.currentSessionId());
        assertFalse(entries.isEmpty());
    }

    @Test
    void searchFindsMatchingMessages() {
        store.startSession();
        store.appendMessage("user", "deploy to production");
        store.appendMessage("assistant", "deploying now");
        store.appendMessage("user", "check the logs");

        var results = store.search("deploy");
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.containsKey("sessionId")));
        assertTrue(results.stream().allMatch(r -> r.containsKey("snippet")));
        assertTrue(results.stream().allMatch(r -> r.containsKey("role")));
    }

    @Test
    void searchReturnsEmptyForNoMatch() {
        store.startSession();
        store.appendMessage("user", "hello world");

        var results = store.search("nonexistent-query-xyz");
        assertTrue(results.isEmpty());
    }

    @Test
    void exportSessionAsMarkdown() {
        store.startSession();
        store.appendMessage("user", "hello");
        store.appendMessage("assistant", "hi there");

        String md = store.exportSession(store.currentSessionId(), "markdown");
        assertNotNull(md);
        assertTrue(md.contains("# Conversation:"));
        assertTrue(md.contains("**User**"));
        assertTrue(md.contains("**Assistant**"));
        assertTrue(md.contains("hello"));
        assertTrue(md.contains("hi there"));
    }

    @Test
    void exportSessionAsJson() {
        store.startSession();
        store.appendMessage("user", "test message");

        String json = store.exportSession(store.currentSessionId(), "json");
        assertNotNull(json);
        assertTrue(json.contains("\"content\""));
        assertTrue(json.contains("test message"));
    }

    @Test
    void exportNonExistentSessionReturnsNull() {
        assertNull(store.exportSession("nonexistent", "markdown"));
    }

    @Test
    void deleteSession() {
        store.startSession();
        String id = store.currentSessionId();
        store.appendMessage("user", "hello");

        assertTrue(store.deleteSession(id));
        assertTrue(store.loadSession(id).isEmpty());
    }

    @Test
    void deleteNonExistentSessionReturnsFalse() {
        assertFalse(store.deleteSession("nonexistent"));
    }

    @Test
    void searchAcrossMultipleSessions() {
        store.startSession();
        store.appendMessage("user", "kubernetes cluster issue");

        var store2 = new ConversationStore(tempDir);
        store2.startSession();
        store2.appendMessage("user", "kubernetes pod restart");

        var results = store.search("kubernetes");
        assertEquals(2, results.size());
    }

    @Test
    void searchIsCaseInsensitive() {
        store.startSession();
        store.appendMessage("user", "Docker compose setup");

        var results = store.search("docker");
        assertEquals(1, results.size());
    }

    @Test
    void previewTruncatesLongMessages() {
        store.startSession();
        store.appendMessage("user", "A".repeat(100));

        var sessions = store.listSessions();
        String preview = sessions.get(0).get("preview");
        assertTrue(preview.length() <= 60);
        assertTrue(preview.endsWith("..."));
    }

    @Test
    void emptySessionPreview() throws IOException {
        Path file = tempDir.resolve("empty01.jsonl");
        Files.writeString(file, "{\"type\":\"session_start\",\"sessionId\":\"empty01\",\"timestamp\":\"2026-01-01T00:00:00Z\"}\n");

        var sessions = store.listSessions();
        var session = sessions.stream()
                .filter(s -> "empty01".equals(s.get("id")))
                .findFirst().orElseThrow();
        assertEquals("(empty)", session.get("preview"));
    }

    @Test
    void sessionsHaveLastModifiedTimestamp() {
        store.startSession();
        store.appendMessage("user", "test");

        var sessions = store.listSessions();
        assertNotNull(sessions.get(0).get("lastModified"));
    }

    @Test
    void exportMarkdownContainsSessionTimestamp() {
        store.startSession();
        store.appendMessage("user", "hello");

        String md = store.exportSession(store.currentSessionId(), "markdown");
        assertTrue(md.contains("*Session started:"));
    }

    @Test
    void setTitleCreatesMetaFile() {
        store.startSession();
        String id = store.currentSessionId();
        store.appendMessage("user", "test");

        assertTrue(store.setTitle(id, "My Chat"));
        Path metaFile = tempDir.resolve(id + ".meta.json");
        assertTrue(Files.exists(metaFile));
    }

    @Test
    void getTitleReturnsSetTitle() {
        store.startSession();
        String id = store.currentSessionId();
        store.appendMessage("user", "test");

        store.setTitle(id, "Important Conversation");
        assertEquals("Important Conversation", store.getTitle(id));
    }

    @Test
    void getTitleReturnsNullWhenNoTitle() {
        store.startSession();
        assertNull(store.getTitle(store.currentSessionId()));
    }

    @Test
    void setTitleReturnsFalseForNonExistentSession() {
        assertFalse(store.setTitle("nonexistent", "Title"));
    }

    @Test
    void setTitleOverwritesPrevious() {
        store.startSession();
        String id = store.currentSessionId();
        store.appendMessage("user", "test");

        store.setTitle(id, "First");
        store.setTitle(id, "Second");
        assertEquals("Second", store.getTitle(id));
    }

    @Test
    void listSessionsIncludesTitle() {
        store.startSession();
        String id = store.currentSessionId();
        store.appendMessage("user", "test");
        store.setTitle(id, "Named Session");

        var sessions = store.listSessions();
        var session = sessions.stream().filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
        assertEquals("Named Session", session.get("title"));
    }

    @Test
    void listSessionsOmitsTitleWhenNotSet() {
        store.startSession();
        String id = store.currentSessionId();
        store.appendMessage("user", "test");

        var sessions = store.listSessions();
        var session = sessions.stream().filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
        assertNull(session.get("title"));
    }

    @Test
    void deleteSessionRemovesMetaFile() {
        store.startSession();
        String id = store.currentSessionId();
        store.appendMessage("user", "test");
        store.setTitle(id, "Titled");

        Path metaFile = tempDir.resolve(id + ".meta.json");
        assertTrue(Files.exists(metaFile));

        store.deleteSession(id);
        assertFalse(Files.exists(metaFile));
    }

    @Test
    void endSessionAppendsSessionEndEntry() {
        String id = store.startSession();
        store.appendMessage("user", "hello");
        store.endSession();

        var entries = store.loadSession(id);
        var endEntry = entries.stream()
                .filter(e -> "session_end".equals(e.get("type")))
                .findFirst();
        assertTrue(endEntry.isPresent());
        assertEquals(id, endEntry.get().get("sessionId"));
        assertNotNull(endEntry.get().get("timestamp"));
    }

    @Test
    void endSessionDoesNothingWithoutStart() {
        assertDoesNotThrow(() -> store.endSession());
    }

    @Test
    void endSessionIncludesSessionId() {
        String id = store.startSession();
        store.endSession();

        var entries = store.loadSession(id);
        long endCount = entries.stream()
                .filter(e -> "session_end".equals(e.get("type")))
                .count();
        assertEquals(1, endCount);
    }

    @Test
    void searchReturnsSnippetWithHighlight() {
        store.startSession();
        store.appendMessage("user", "I need help with kubernetes deployment");

        var results = store.search("kubernetes");
        assertEquals(1, results.size());
        String snippet = (String) results.get(0).get("snippet");
        assertTrue(snippet.contains(">>>kubernetes<<<"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchReturnsContext() {
        store.startSession();
        store.appendMessage("user", "how to deploy?");
        store.appendMessage("assistant", "use kubernetes apply command");
        store.appendMessage("user", "thanks that worked");

        var results = store.search("kubernetes");
        assertEquals(1, results.size());
        var result = results.get(0);
        assertEquals("assistant", result.get("role"));
        List<Map<String, String>> context = (List<Map<String, String>>) result.get("context");
        assertNotNull(context);
        assertEquals(2, context.size());
        assertEquals("user", context.get(0).get("role"));
        assertTrue(context.get(0).get("content").contains("deploy"));
    }

    @Test
    void searchWithLimit() {
        store.startSession();
        for (int i = 0; i < 20; i++) {
            store.appendMessage("user", "message about docker number " + i);
        }

        var results = store.search("docker", 5);
        assertEquals(5, results.size());
    }

    @Test
    void searchGroupedGroupsBySession() {
        store.startSession();
        store.appendMessage("user", "first kubernetes question");
        store.appendMessage("assistant", "kubernetes answer");

        var store2 = new ConversationStore(tempDir);
        store2.startSession();
        store2.appendMessage("user", "second kubernetes question");

        var grouped = store.searchGrouped("kubernetes", 10);
        assertEquals(2, grouped.size());
        assertTrue(grouped.stream().allMatch(s -> s.containsKey("matchCount")));
        assertTrue(grouped.stream().allMatch(s -> s.containsKey("matches")));
    }

    @Test
    void searchGroupedLimitsSessionCount() {
        for (int i = 0; i < 5; i++) {
            var s = new ConversationStore(tempDir);
            s.startSession();
            s.appendMessage("user", "deploy question " + i);
        }

        var grouped = store.searchGrouped("deploy", 3);
        assertTrue(grouped.size() <= 3);
    }

    @Test
    void searchGroupedIncludesTitle() {
        store.startSession();
        String id = store.currentSessionId();
        store.appendMessage("user", "kubernetes setup");
        store.setTitle(id, "K8s Setup");

        var grouped = store.searchGrouped("kubernetes", 5);
        assertEquals(1, grouped.size());
        assertEquals("K8s Setup", grouped.get(0).get("title"));
    }

    @Test
    void snippetTruncatesLongContent() {
        store.startSession();
        String longContent = "prefix ".repeat(50) + "FINDME" + " suffix".repeat(50);
        store.appendMessage("user", longContent);

        var results = store.search("FINDME");
        assertEquals(1, results.size());
        String snippet = (String) results.get(0).get("snippet");
        assertTrue(snippet.contains(">>>FINDME<<<"));
        assertTrue(snippet.startsWith("..."));
        assertTrue(snippet.endsWith("..."));
    }
}
