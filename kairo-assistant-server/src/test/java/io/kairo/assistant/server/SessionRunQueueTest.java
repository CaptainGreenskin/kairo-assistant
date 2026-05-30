package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionRunQueueTest {

    private final SessionRunQueue queue = new SessionRunQueue();

    @Test
    void firstSubmitRunsImmediately() {
        List<String> ran = new ArrayList<>();
        boolean started = queue.submit("s", () -> ran.add("a"));
        assertTrue(started);
        assertEquals(List.of("a"), ran);
        assertTrue(queue.isActive("s"));
    }

    @Test
    void secondSubmitIsQueuedThenDrained() {
        List<String> ran = new ArrayList<>();
        // task a stays "active" (doesn't auto-complete) until we call onComplete
        queue.submit("s", () -> ran.add("a"));
        boolean started = queue.submit("s", () -> ran.add("b"));
        assertFalse(started);
        assertEquals(1, queue.queuedCount("s"));
        assertEquals(List.of("a"), ran);

        queue.onComplete("s"); // a finished → drains b
        assertEquals(List.of("a", "b"), ran);
        assertEquals(0, queue.queuedCount("s"));
    }

    @Test
    void onCompleteWithEmptyQueueClearsActive() {
        queue.submit("s", () -> {});
        assertTrue(queue.isActive("s"));
        queue.onComplete("s");
        assertFalse(queue.isActive("s"));
    }

    @Test
    void differentSessionsRunInParallel() {
        List<String> ran = new ArrayList<>();
        assertTrue(queue.submit("s1", () -> ran.add("s1")));
        assertTrue(queue.submit("s2", () -> ran.add("s2")));
        assertEquals(List.of("s1", "s2"), ran);
    }

    @Test
    void fifoOrderPreserved() {
        List<String> ran = new ArrayList<>();
        queue.submit("s", () -> ran.add("a"));
        queue.submit("s", () -> ran.add("b"));
        queue.submit("s", () -> ran.add("c"));
        queue.onComplete("s");
        queue.onComplete("s");
        assertEquals(List.of("a", "b", "c"), ran);
    }
}
