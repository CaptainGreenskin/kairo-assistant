package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.cron.CronTask;
import io.kairo.api.cron.CronScheduler;
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
        controller = new CronController(session, EventBroadcaster.noop(), TestFixtures.noopDashboard());
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
        var broadcastingController = new CronController(session, events::add, TestFixtures.noopDashboard());

        broadcastingController.create(Map.of("cron", "0 9 * * *", "prompt", "test"));

        assertEquals(1, events.size());
        assertEquals("cron_created", events.get(0).get("type"));
        assertEquals("0 9 * * *", events.get(0).get("cron"));
    }

    @Test
    void deleteBroadcastsEvent() {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        var session = TestFixtures.defaultSession(new TestFixtures.StubToolExecutor(), cronScheduler);
        var broadcastingController = new CronController(session, events::add, TestFixtures.noopDashboard());

        var created = broadcastingController.create(Map.of("cron", "0 9 * * *", "prompt", "test"));
        String id = (String) created.get("id");
        events.clear();

        broadcastingController.delete(id);
        assertEquals(1, events.size());
        assertEquals("cron_deleted", events.get(0).get("type"));
        assertEquals(id, events.get(0).get("id"));
    }

    @Test
    void pauseAndResumeFlipPausedFlag() {
        var created = controller.create(Map.of("cron", "0 9 * * *", "prompt", "morning"));
        String id = (String) created.get("id");

        var paused = controller.pause(id);
        assertEquals(Boolean.TRUE, paused.get("paused"));

        var resumed = controller.resume(id);
        assertEquals(Boolean.FALSE, resumed.get("paused"));
    }

    @Test
    void editChangesCronAndPrompt() {
        var created = controller.create(Map.of("cron", "0 9 * * *", "prompt", "morning"));
        String id = (String) created.get("id");

        var edited =
                controller.edit(
                        id, Map.of("cron", "0 10 * * *", "prompt", "later morning"));
        assertEquals("0 10 * * *", edited.get("cron"));
        assertEquals("later morning", edited.get("prompt"));
    }

    @Test
    void triggerFiresOutsideSchedule() {
        var created = controller.create(Map.of("cron", "0 0 1 1 *", "prompt", "new year"));
        String id = (String) created.get("id");
        // trigger is fire-and-forget now (the underlying scheduler call can block on
        // the agent for tens of seconds); the request returns "triggering" and the
        // actual fire happens on a background thread. See CronController for why.
        var result = controller.trigger(id);
        assertEquals("triggering", result.get("status"));
        assertEquals(id, result.get("id"));
    }

    @Test
    void noAgentRequiresScript() {
        var result =
                controller.create(
                        Map.of(
                                "cron",
                                "0 * * * *",
                                "prompt",
                                "ignored",
                                "noAgent",
                                true));
        assertEquals("noAgent=true requires non-blank script", result.get("error"));
    }

    @Test
    void createWithSkillsAndWorkdirSurfacesOnView() {
        var result =
                controller.create(
                        Map.of(
                                "cron",
                                "0 * * * *",
                                "prompt",
                                "do thing",
                                "skills",
                                List.of("alpha", "beta"),
                                "workdir",
                                "/var/work"));
        assertEquals("created", result.get("status"));
        assertEquals(List.of("alpha", "beta"), result.get("skills"));
        assertEquals("/var/work", result.get("workdir"));
    }

    private static class InMemoryCronScheduler implements CronScheduler {
        private final List<CronTask> tasks = new ArrayList<>();

        @Override
        public CronTask create(String cron, String prompt, boolean recurring, boolean durable) {
            var task =
                    new CronTask(
                            UUID.randomUUID().toString().substring(0, 8),
                            cron,
                            prompt,
                            Instant.now(),
                            null,
                            recurring,
                            durable);
            tasks.add(task);
            return task;
        }

        @Override
        public CronTask create(
                String cron, String prompt, io.kairo.api.cron.CronTaskOptions options) {
            if (options.noAgent() && (options.script() == null || options.script().isBlank())) {
                throw new IllegalArgumentException("noAgent=true requires non-blank script");
            }
            var base = create(cron, prompt, options.recurring(), options.durable());
            // Build a new task that includes the M3 options fields.
            var t =
                    new CronTask(
                            base.id(),
                            base.cron(),
                            base.prompt(),
                            base.createdAt(),
                            base.lastFiredAt(),
                            base.recurring(),
                            base.durable(),
                            false,
                            0,
                            null,
                            null,
                            options.skills(),
                            options.workdir(),
                            options.noAgent(),
                            options.script(),
                            options.contextFromTaskId());
            tasks.removeIf(x -> x.id().equals(t.id()));
            tasks.add(t);
            return t;
        }

        @Override
        public boolean delete(String taskId) {
            return tasks.removeIf(t -> t.id().equals(taskId));
        }

        @Override
        public List<CronTask> list() {
            return List.copyOf(tasks);
        }

        @Override
        public java.util.Optional<CronTask> pause(String taskId) {
            return mutate(taskId, t -> t.withPaused(true));
        }

        @Override
        public java.util.Optional<CronTask> resume(String taskId) {
            return mutate(taskId, t -> t.withPaused(false));
        }

        @Override
        public java.util.Optional<CronTask> edit(
                String taskId, String newCron, String newPrompt) {
            return mutate(taskId, t -> t.withCronAndPrompt(newCron, newPrompt));
        }

        @Override
        public boolean trigger(String taskId) {
            return tasks.stream().anyMatch(t -> t.id().equals(taskId));
        }

        private java.util.Optional<CronTask> mutate(
                String taskId, java.util.function.Function<CronTask, CronTask> f) {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id().equals(taskId)) {
                    CronTask updated = f.apply(tasks.get(i));
                    tasks.set(i, updated);
                    return java.util.Optional.of(updated);
                }
            }
            return java.util.Optional.empty();
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}
    }
}
