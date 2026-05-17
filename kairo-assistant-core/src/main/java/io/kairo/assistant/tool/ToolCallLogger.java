package io.kairo.assistant.tool;

import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolInvocation;
import io.kairo.api.tool.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ToolCallLogger implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLogger.class);

    private static final int DEFAULT_HISTORY_SIZE = 200;

    private final ToolExecutor delegate;
    private final int maxEntries;
    private final ConcurrentLinkedDeque<ToolCallRecord> history = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong totalDurationMs = new AtomicLong();
    private final CopyOnWriteArrayList<Consumer<ToolCallRecord>> listeners = new CopyOnWriteArrayList<>();

    public ToolCallLogger(ToolExecutor delegate) {
        this(delegate, DEFAULT_HISTORY_SIZE);
    }

    public ToolCallLogger(ToolExecutor delegate, int maxEntries) {
        this.delegate = delegate;
        this.maxEntries = maxEntries;
    }

    @Override
    public Mono<ToolResult> execute(String toolName, Map<String, Object> input) {
        long start = System.currentTimeMillis();
        return delegate.execute(toolName, input)
                .doOnSuccess(result -> record(toolName, start, result))
                .doOnError(e -> recordError(toolName, start, e.getMessage()));
    }

    @Override
    public Mono<ToolResult> execute(String toolName, Map<String, Object> input, Duration timeout) {
        long start = System.currentTimeMillis();
        return delegate.execute(toolName, input, timeout)
                .doOnSuccess(result -> record(toolName, start, result))
                .doOnError(e -> recordError(toolName, start, e.getMessage()));
    }

    @Override
    public Flux<ToolResult> executeParallel(List<ToolInvocation> invocations) {
        return delegate.executeParallel(invocations)
                .doOnNext(result -> {
                    totalCalls.incrementAndGet();
                    if (result.isError()) totalErrors.incrementAndGet();
                });
    }

    private void record(String toolName, long startMs, ToolResult result) {
        long durationMs = System.currentTimeMillis() - startMs;
        totalCalls.incrementAndGet();
        totalDurationMs.addAndGet(durationMs);
        boolean isError = result != null && result.isError();
        if (isError) totalErrors.incrementAndGet();

        addEntry(new ToolCallRecord(
                toolName, Instant.ofEpochMilli(startMs), durationMs,
                !isError, isError ? result.content() : null));
    }

    private void recordError(String toolName, long startMs, String error) {
        long durationMs = System.currentTimeMillis() - startMs;
        totalCalls.incrementAndGet();
        totalErrors.incrementAndGet();
        totalDurationMs.addAndGet(durationMs);

        addEntry(new ToolCallRecord(
                toolName, Instant.ofEpochMilli(startMs), durationMs, false, error));
    }

    private void addEntry(ToolCallRecord entry) {
        history.addFirst(entry);
        while (history.size() > maxEntries) {
            history.removeLast();
        }
        for (Consumer<ToolCallRecord> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (Exception e) {
                log.warn("Tool call listener threw exception for {}: {}", entry.toolName(), e.getMessage());
            }
        }
    }

    public void addListener(Consumer<ToolCallRecord> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<ToolCallRecord> listener) {
        listeners.remove(listener);
    }

    public List<ToolCallRecord> recentCalls(int limit) {
        List<ToolCallRecord> result = new ArrayList<>();
        int count = 0;
        for (ToolCallRecord r : history) {
            if (count >= limit) break;
            result.add(r);
            count++;
        }
        return Collections.unmodifiableList(result);
    }

    public List<ToolCallRecord> allCalls() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public long totalCalls() { return totalCalls.get(); }
    public long totalErrors() { return totalErrors.get(); }
    public long totalDurationMs() { return totalDurationMs.get(); }
    public long averageDurationMs() {
        long calls = totalCalls.get();
        return calls > 0 ? totalDurationMs.get() / calls : 0;
    }

    public ToolExecutor delegate() { return delegate; }

    public record ToolCallRecord(
            String toolName,
            Instant timestamp,
            long durationMs,
            boolean success,
            String error) {}
}
