/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.core.session.SessionKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-memory registry for asynchronous agent runs (the {@code /v1/runs} API).
 *
 * <p>A "run" is a detached {@code UnifiedGateway.route(...)} execution that outlives the HTTP
 * request that started it: the client polls {@code GET /v1/runs/{id}} for status or subscribes to
 * {@code GET /v1/runs/{id}/events} (SSE) for the lifecycle stream. State is process-local and
 * bounded — completed runs are evicted oldest-first past {@link #MAX_RETAINED}.
 */
@Component
public class RunRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunRegistry.class);
    private static final int MAX_RETAINED = 200;

    public enum Status {
        PENDING,
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        STOPPED
    }

    /** Mutable run state. Fields touched from the request thread and the reactor thread, so volatile. */
    public static final class Run {
        final String id;
        final SessionKey key;
        final Instant createdAt = Instant.now();
        volatile Status status = Status.PENDING;
        volatile Instant finishedAt;
        volatile String result;
        volatile String error;
        final Sinks.Many<String> events = Sinks.many().multicast().onBackpressureBuffer(256);
        volatile Disposable disposable;

        Run(String id, SessionKey key) {
            this.id = id;
            this.key = key;
        }

        public String id() {
            return id;
        }

        public SessionKey key() {
            return key;
        }

        public Status status() {
            return status;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();

    public Run create(String id, SessionKey key) {
        Run run = new Run(id, key);
        runs.put(id, run);
        order.addLast(id);
        evictExcess();
        emit(run, "run.created", Map.of());
        return run;
    }

    public void attachDisposable(String id, Disposable disposable) {
        Run run = runs.get(id);
        if (run != null) run.disposable = disposable;
    }

    public void markQueued(String id) {
        Run run = runs.get(id);
        if (run != null && run.status == Status.PENDING) {
            run.status = Status.QUEUED;
            emit(run, "run.queued", Map.of());
        }
    }

    public void markRunning(String id) {
        Run run = runs.get(id);
        if (run != null && (run.status == Status.PENDING || run.status == Status.QUEUED)) {
            run.status = Status.RUNNING;
            emit(run, "run.running", Map.of());
        }
    }

    public void succeed(String id, String result) {
        Run run = runs.get(id);
        if (run == null || isTerminal(run.status)) return;
        run.result = result;
        run.status = Status.SUCCEEDED;
        run.finishedAt = Instant.now();
        emit(run, "run.succeeded", Map.of("result", result == null ? "" : result));
        run.events.tryEmitComplete();
    }

    public void fail(String id, String error) {
        Run run = runs.get(id);
        if (run == null || isTerminal(run.status)) return;
        run.error = error;
        run.status = Status.FAILED;
        run.finishedAt = Instant.now();
        emit(run, "run.failed", Map.of("error", error == null ? "" : error));
        run.events.tryEmitComplete();
    }

    /** Marks a run stopped and disposes its underlying subscription. Returns false if unknown/terminal. */
    public boolean stop(String id) {
        Run run = runs.get(id);
        if (run == null || isTerminal(run.status)) return false;
        run.status = Status.STOPPED;
        run.finishedAt = Instant.now();
        if (run.disposable != null && !run.disposable.isDisposed()) {
            run.disposable.dispose();
        }
        emit(run, "run.stopped", Map.of());
        run.events.tryEmitComplete();
        return true;
    }

    public Run get(String id) {
        return runs.get(id);
    }

    /** SSE event stream for a run, or null if the run is unknown. */
    public Flux<String> events(String id) {
        Run run = runs.get(id);
        return run == null ? null : run.events.asFlux();
    }

    public Map<String, Object> snapshot(Run run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", run.id);
        m.put("status", run.status.name().toLowerCase());
        m.put("createdAt", run.createdAt.toString());
        if (run.finishedAt != null) m.put("finishedAt", run.finishedAt.toString());
        if (run.result != null) m.put("result", run.result);
        if (run.error != null) m.put("error", run.error);
        return m;
    }

    public void emit(String id, String type, Map<String, Object> data) {
        Run run = runs.get(id);
        if (run != null) emit(run, type, data);
    }

    private void emit(Run run, String type, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("run_id", run.id);
        event.putAll(data);
        try {
            run.events.tryEmitNext("data: " + mapper.writeValueAsString(event) + "\n\n");
        } catch (Exception e) {
            log.debug("Failed to serialize run event {}: {}", type, e.getMessage());
        }
    }

    private static boolean isTerminal(Status s) {
        return s == Status.SUCCEEDED || s == Status.FAILED || s == Status.STOPPED;
    }

    private void evictExcess() {
        while (runs.size() > MAX_RETAINED) {
            String oldest = order.pollFirst();
            if (oldest == null) break;
            Run run = runs.get(oldest);
            // Only evict finished runs; if the oldest is still active, requeue and stop trimming.
            if (run != null && !isTerminal(run.status)) {
                order.addLast(oldest);
                break;
            }
            runs.remove(oldest);
        }
    }
}
