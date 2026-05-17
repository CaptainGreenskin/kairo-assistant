package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryControllerTest {

    private MemoryController controller;

    @BeforeEach
    void setUp() {
        controller = new MemoryController(TestFixtures.defaultSession(), EventBroadcaster.noop());
    }

    @Test
    void listReturnsEmptyByDefault() {
        var result = controller.list("GLOBAL").block();
        assertNotNull(result);
        assertEquals(0, result.get("total"));
        assertEquals("GLOBAL", result.get("scope"));
    }

    @Test
    void saveAndRetrieve() {
        var saved = controller.save(Map.of("content", "remember this", "importance", "0.8")).block();
        assertNotNull(saved);
        assertEquals("saved", saved.get("status"));
        String id = (String) saved.get("id");

        var entry = controller.get(id).block();
        assertNotNull(entry);
        assertEquals("remember this", entry.get("content"));
    }

    @Test
    void saveRejectsEmpty() {
        var result = controller.save(Map.of("content", "")).block();
        assertEquals("content is required", result.get("error"));
    }

    @Test
    void deleteEntry() {
        var saved = controller.save(Map.of("content", "temp")).block();
        String id = (String) saved.get("id");

        var deleted = controller.delete(id).block();
        assertEquals("deleted", deleted.get("status"));
    }

    @Test
    void searchReturnsMatches() {
        controller.save(Map.of("content", "the quick brown fox")).block();
        controller.save(Map.of("content", "lazy dog")).block();

        var result = controller.search("fox", "GLOBAL").block();
        assertNotNull(result);
        assertEquals("fox", result.get("query"));
    }

    @Test
    void getNonExistentReturnsError() {
        var result = controller.get("nonexistent").block();
        assertNotNull(result);
        assertEquals("not found", result.get("error"));
    }

    @Test
    void saveWithTags() {
        var saved = controller.save(Map.of(
                "content", "tagged entry",
                "tags", List.of("important", "work"))).block();
        assertNotNull(saved);
        assertEquals("saved", saved.get("status"));
    }

    @Test
    void saveBroadcastsEvent() {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        var broadcastController = new MemoryController(TestFixtures.defaultSession(), events::add);

        broadcastController.save(Map.of("content", "test broadcast")).block();

        assertEquals(1, events.size());
        assertEquals("memory_saved", events.get(0).get("type"));
        assertEquals("test broadcast", events.get(0).get("content"));
    }

    @Test
    void saveRejectsInvalidImportance() {
        var result = controller.save(Map.of("content", "test", "importance", "not-a-number")).block();
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("invalid importance"));
    }

    @Test
    void saveRejectsImportanceOutOfRange() {
        var result = controller.save(Map.of("content", "test", "importance", "1.5")).block();
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("between 0.0 and 1.0"));
    }

    @Test
    void saveRejectsNegativeImportance() {
        var result = controller.save(Map.of("content", "test", "importance", "-0.1")).block();
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("between 0.0 and 1.0"));
    }

    @Test
    void saveAcceptsValidImportance() {
        var result = controller.save(Map.of("content", "test", "importance", "0.7")).block();
        assertNotNull(result);
        assertEquals("saved", result.get("status"));
    }

    @Test
    void deleteBroadcastsEvent() {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        var broadcastController = new MemoryController(TestFixtures.defaultSession(), events::add);

        var saved = broadcastController.save(Map.of("content", "to delete")).block();
        String id = (String) saved.get("id");
        events.clear();

        broadcastController.delete(id).block();
        assertEquals(1, events.size());
        assertEquals("memory_deleted", events.get(0).get("type"));
        assertEquals(id, events.get(0).get("id"));
    }
}
