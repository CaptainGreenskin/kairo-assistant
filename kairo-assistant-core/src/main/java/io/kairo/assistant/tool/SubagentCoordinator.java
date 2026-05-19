package io.kairo.assistant.tool;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class SubagentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SubagentCoordinator.class);
    private static final int MAX_CONCURRENT_PER_SESSION = 3;

    private final ConcurrentHashMap<String, TaskEntry> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> sessionCounts = new ConcurrentHashMap<>();

    public static class TaskEntry {
        private final String taskId;
        private final String sessionId;
        private final String description;
        private final Instant startedAt;
        private volatile TaskStatus status;
        private volatile String result;

        TaskEntry(String taskId, String sessionId, String description,
                  Instant startedAt, TaskStatus status, String result) {
            this.taskId = taskId;
            this.sessionId = sessionId;
            this.description = description;
            this.startedAt = startedAt;
            this.status = status;
            this.result = result;
        }

        public String taskId() { return taskId; }
        public String sessionId() { return sessionId; }
        public String description() { return description; }
        public Instant startedAt() { return startedAt; }
        public TaskStatus status() { return status; }
        public String result() { return result; }

        void complete(String result) {
            this.result = result;
            this.status = TaskStatus.COMPLETED;
        }

        void fail(String error) {
            this.result = error;
            this.status = TaskStatus.FAILED;
        }
    }

    public enum TaskStatus { RUNNING, COMPLETED, FAILED }

    public String submit(String sessionId, String description, Agent subAgent, String prompt) {
        AtomicInteger count = sessionCounts.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        if (count.get() >= MAX_CONCURRENT_PER_SESSION) {
            return null;
        }

        String taskId = "task-" + System.currentTimeMillis() + "-" + Math.abs(description.hashCode() % 1000);
        count.incrementAndGet();

        TaskEntry entry = new TaskEntry(taskId, sessionId, description,
                Instant.now(), TaskStatus.RUNNING, null);
        tasks.put(taskId, entry);

        subAgent.call(Msg.of(MsgRole.USER, prompt))
                .subscribe(
                        response -> {
                            entry.complete(response.text());
                            count.decrementAndGet();
                            log.info("Subagent task [{}] completed", taskId);
                        },
                        error -> {
                            entry.fail(error.getMessage());
                            count.decrementAndGet();
                            log.warn("Subagent task [{}] failed: {}", taskId, error.getMessage());
                        });

        log.info("Subagent task [{}] submitted for session [{}]: {}", taskId, sessionId, description);
        return taskId;
    }

    public Mono<Msg> submitAndWait(String sessionId, String description, Agent subAgent, String prompt) {
        AtomicInteger count = sessionCounts.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        if (count.get() >= MAX_CONCURRENT_PER_SESSION) {
            return Mono.error(new IllegalStateException(
                    "Max concurrent subagents (" + MAX_CONCURRENT_PER_SESSION + ") reached for session"));
        }
        count.incrementAndGet();
        return subAgent.call(Msg.of(MsgRole.USER, prompt))
                .doFinally(signal -> count.decrementAndGet());
    }

    public TaskEntry getTask(String taskId) {
        return tasks.get(taskId);
    }

    public List<TaskEntry> getSessionTasks(String sessionId) {
        return tasks.values().stream()
                .filter(e -> e.sessionId().equals(sessionId))
                .toList();
    }

    public int activeCount(String sessionId) {
        AtomicInteger count = sessionCounts.get(sessionId);
        return count != null ? count.get() : 0;
    }

    public void cleanup(String sessionId) {
        tasks.entrySet().removeIf(e -> e.getValue().sessionId().equals(sessionId));
        sessionCounts.remove(sessionId);
    }

    public Map<String, TaskEntry> allTasks() {
        return Map.copyOf(tasks);
    }
}
