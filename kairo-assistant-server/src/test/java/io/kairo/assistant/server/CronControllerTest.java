package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.cron.CronTask;
import io.kairo.core.cron.CronScheduler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CronControllerTest {

    private CronController controller;
    private InMemoryCronScheduler cronScheduler;

    @BeforeEach
    void setUp() {
        cronScheduler = new InMemoryCronScheduler();
        var session = TestFixtures.defaultSession(new TestFixtures.StubToolExecutor(), cronScheduler);
        controller = new CronController(session, EventBroadcaster.noop());
    }

    @Test
    void listReturnsEmptyByDefault() {
        var result = controller.list();
        assertEquals(0, result.get("total"));
    }

    @Test
    void createReturnsCronTask() {
        var result = controller.create(Map.of(
                "cron", "0 9 * * *",
                "prompt", "Good morning",
                "recurring", "true"));
        assertEquals("created", result.get("status"));
        assertNotNull(result.get("id"));
        assertEquals("0 9 * * *", result.get("cron"));
        assertEquals("Good morning", result.get("prompt"));
    }

    @Test
    void createRejectsMissingFields() {
        var result = controller.create(Map.of("cron", ""));
        assertEquals("cron and prompt are required", result.get("error"));
    }

    @Test
    void listReturnsCreatedTasks() {
        controller.create(Map.of("cron", "0 9 * * *", "prompt", "hello"));
        controller.create(Map.of("cron", "*/5 * * * *", "prompt", "check"));

        var result = controller.list();
        assertEquals(2, result.get("total"));
        @SuppressWarnings("unchecked")
        var tasks = (List<Map<String, Object>>) result.get("tasks");
        assertEquals(2, tasks.size());
    }

    @Test
    void deleteRemovesTask() {
        var created = controller.create(Map.of("cron", "0 9 * * *", "prompt", "test"));
        String id = (String) created.get("id");

        var result = controller.delete(id);
        assertEquals("deleted", result.get("status"));

        var list = controller.list();
        assertEquals(0, list.get("total"));
    }

    @Test
    void deleteNonExistentReturnsError() {
        var result = controller.delete("nonexistent");
        assertEquals("task not found", result.get("error"));
    }

    @Test
    void createWithDurableFlag() {
        var result = controller.create(Map.of(
                "cron", "0 9 * * 1-5",
                "prompt", "weekday task",
                "durable", "true"));
        assertEquals(true, result.get("durable"));
    }

    @Test
    void createBroadcastsEvent() {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        var session = TestFixtures.defaultSession(new TestFixtures.StubToolExecutor(), cronScheduler);
        var broadcastingController = new CronController(session, events::add);

        broadcastingController.create(Map.of("cron", "0 9 * * *", "prompt", "test"));

        assertEquals(1, events.size());
        assertEquals("cron_created", events.get(0).get("type"));
        assertEquals("0 9 * * *", events.get(0).get("cron"));
    }

    @Test
    void deleteBroadcastsEvent() {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        var session = TestFixtures.defaultSession(new TestFixtures.StubToolExecutor(), cronScheduler);
        var broadcastingController = new CronController(session, events::add);

        var created = broadcastingController.create(Map.of("cron", "0 9 * * *", "prompt", "test"));
        String id = (String) created.get("id");
        events.clear();

        broadcastingController.delete(id);
        assertEquals(1, events.size());
        assertEquals("cron_deleted", events.get(0).get("type"));
        assertEquals(id, events.get(0).get("id"));
    }

    private static class InMemoryCronScheduler implements CronScheduler {
        private final List<CronTask> tasks = new ArrayList<>();

        @Override
        public CronTask create(String cron, String prompt, boolean recurring, boolean durable) {
            var task = new CronTask(
                    UUID.randomUUID().toString().substring(0, 8),
                    cron, prompt, Instant.now(), null, recurring, durable);
            tasks.add(task);
            return task;
        }

        @Override
        public boolean delete(String taskId) {
            return tasks.removeIf(t -> t.id().equals(taskId));
        }

        @Override
        public List<CronTask> list() {
            return List.copyOf(tasks);
        }

        @Override public void start() {}
        @Override public void stop() {}
    }
}
