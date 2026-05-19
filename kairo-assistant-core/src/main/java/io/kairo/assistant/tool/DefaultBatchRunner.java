package io.kairo.assistant.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultBatchRunner implements BatchRunTool.BatchRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultBatchRunner.class);

    private final ExecutorService executor;
    private final Function<String, String> promptExecutor;
    private final ConcurrentHashMap<String, BatchState> batches = new ConcurrentHashMap<>();

    public DefaultBatchRunner(Function<String, String> promptExecutor, int poolSize) {
        this.promptExecutor = promptExecutor;
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "batch-runner");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String submit(List<String> prompts, boolean parallel, String sessionId) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        BatchState state = new BatchState(id, prompts.size(), Instant.now());
        batches.put(id, state);

        if (parallel) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < prompts.size(); i++) {
                final int idx = i;
                final String prompt = prompts.get(i);
                futures.add(executor.submit(() -> executeOne(state, idx, prompt)));
            }
            state.futures = futures;
        } else {
            executor.submit(() -> {
                for (int i = 0; i < prompts.size() && !state.cancelled; i++) {
                    executeOne(state, i, prompts.get(i));
                }
            });
        }

        log.info("Batch [{}] submitted: {} tasks ({})",
                id, prompts.size(), parallel ? "parallel" : "sequential");
        return id;
    }

    private void executeOne(BatchState state, int index, String prompt) {
        if (state.cancelled) return;
        try {
            String result = promptExecutor.apply(prompt);
            state.results.put(index, result != null ? result : "(no result)");
            state.completed.incrementAndGet();
        } catch (Exception e) {
            state.results.put(index, "ERROR: " + e.getMessage());
            state.failed.incrementAndGet();
            log.error("Batch [{}] task {} failed: {}", state.id, index, e.getMessage());
        }
    }

    @Override
    public String status(String batchId) {
        BatchState state = batches.get(batchId);
        if (state == null) return "Batch not found: " + batchId;

        StringBuilder sb = new StringBuilder();
        sb.append("Batch: ").append(batchId).append("\n");
        sb.append("Total: ").append(state.total).append("\n");
        sb.append("Completed: ").append(state.completed.get()).append("\n");
        sb.append("Failed: ").append(state.failed.get()).append("\n");
        sb.append("Status: ").append(state.cancelled ? "CANCELLED"
                : (state.completed.get() + state.failed.get() >= state.total ? "DONE" : "RUNNING"))
                .append("\n");

        if (!state.results.isEmpty()) {
            sb.append("\nResults:\n");
            state.results.forEach((idx, result) -> {
                String preview = result.length() > 100
                        ? result.substring(0, 100) + "..." : result;
                sb.append("  [").append(idx).append("] ").append(preview).append("\n");
            });
        }
        return sb.toString();
    }

    @Override
    public String allStatus() {
        if (batches.isEmpty()) return "No batches.";
        StringBuilder sb = new StringBuilder();
        batches.forEach((id, state) -> {
            String statusStr = state.cancelled ? "CANCELLED"
                    : (state.completed.get() + state.failed.get() >= state.total ? "DONE" : "RUNNING");
            sb.append(id).append(": ").append(state.completed.get())
                    .append("/").append(state.total).append(" (").append(statusStr).append(")\n");
        });
        return sb.toString();
    }

    @Override
    public boolean cancel(String batchId) {
        BatchState state = batches.get(batchId);
        if (state == null) return false;
        state.cancelled = true;
        if (state.futures != null) {
            state.futures.forEach(f -> f.cancel(true));
        }
        return true;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static class BatchState {
        final String id;
        final int total;
        final Instant startedAt;
        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final ConcurrentHashMap<Integer, String> results = new ConcurrentHashMap<>();
        volatile boolean cancelled = false;
        volatile List<Future<?>> futures;

        BatchState(String id, int total, Instant startedAt) {
            this.id = id;
            this.total = total;
            this.startedAt = startedAt;
        }
    }
}
