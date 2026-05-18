package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventBroadcasterTest {

    @Test
    void noopDoesNotThrow() {
        EventBroadcaster noop = EventBroadcaster.noop();
        assertDoesNotThrow(() -> noop.broadcast(Map.of("type", "test")));
    }

    @Test
    void lambdaReceivesEvent() {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        EventBroadcaster broadcaster = events::add;

        broadcaster.broadcast(Map.of("type", "test_event", "data", "hello"));

        assertEquals(1, events.size());
        assertEquals("test_event", events.get(0).get("type"));
    }

    @Test
    void rapidBroadcastsAllDelivered() {
        var count = new AtomicInteger(0);
        EventBroadcaster broadcaster = event -> count.incrementAndGet();

        for (int i = 0; i < 1000; i++) {
            broadcaster.broadcast(Map.of("type", "rapid", "seq", i));
        }

        assertEquals(1000, count.get());
    }

    @Test
    void concurrentBroadcastsAreSafe() throws Exception {
        var count = new AtomicInteger(0);
        EventBroadcaster broadcaster = event -> count.incrementAndGet();

        int threads = 8;
        int perThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        broadcaster.broadcast(Map.of("type", "concurrent"));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threads * perThread, count.get());
    }

    @Test
    void broadcastWithNullEventHandledGracefully() {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        EventBroadcaster broadcaster = events::add;

        broadcaster.broadcast(Map.of());
        assertEquals(1, events.size());
        assertTrue(events.get(0).isEmpty());
    }

    @Test
    void noopIsFunctionalInterface() {
        EventBroadcaster noop = EventBroadcaster.noop();
        noop.broadcast(Map.of("key", "value"));
        noop.broadcast(Map.of("another", "event"));
    }

    @Test
    void eventDataPreservedInLambda() {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        EventBroadcaster broadcaster = events::add;

        broadcaster.broadcast(Map.of("type", "chat", "sender", "user1", "content", "hello"));

        assertEquals("chat", events.get(0).get("type"));
        assertEquals("user1", events.get(0).get("sender"));
        assertEquals("hello", events.get(0).get("content"));
    }
}
