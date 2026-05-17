package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedbackControllerTest {

    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        controller = new FeedbackController(TestFixtures.defaultSession(), EventBroadcaster.noop());
    }

    @Test
    void submitFeedbackReturnsOk() {
        var result = controller.submitFeedback(Map.of("content", "test response", "rating", "positive"));
        assertEquals("ok", result.get("status"));
    }

    @Test
    void listFeedbackReturnsEntries() {
        controller.submitFeedback(Map.of("content", "good", "rating", "positive"));
        controller.submitFeedback(Map.of("content", "bad", "rating", "negative", "feedback", "too slow"));

        var result = controller.listFeedback(10);
        assertEquals(2, result.get("total"));
        assertEquals(1L, result.get("positive"));
        assertEquals(1L, result.get("negative"));
    }

    @Test
    void listFeedbackEmptyByDefault() {
        var result = controller.listFeedback(10);
        assertEquals(0, result.get("total"));
        assertEquals(0L, result.get("positive"));
        assertEquals(0L, result.get("negative"));
    }

    @Test
    void feedbackWithMissingFieldsStillWorks() {
        var result = controller.submitFeedback(Map.of());
        assertEquals("ok", result.get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFeedbackRespectsLimit() {
        for (int i = 0; i < 10; i++) {
            controller.submitFeedback(Map.of("content", "msg-" + i, "rating", "positive"));
        }
        var result = controller.listFeedback(3);
        assertEquals(10, result.get("total"));
        var entries = (List<?>) result.get("entries");
        assertEquals(3, entries.size());
    }

    @Test
    void submitBroadcastsEvent() {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        var broadcastController = new FeedbackController(TestFixtures.defaultSession(), events::add);

        broadcastController.submitFeedback(Map.of("content", "test", "rating", "positive"));

        assertEquals(1, events.size());
        assertEquals("feedback", events.get(0).get("type"));
        assertEquals("positive", events.get(0).get("rating"));
    }
}
