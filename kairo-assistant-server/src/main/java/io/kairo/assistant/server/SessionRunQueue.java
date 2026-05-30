/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Serializes asynchronous runs that share a {@code session_id}: at most one run per session executes
 * at a time, later submissions wait in FIFO order. This gives the {@code /v1/runs} API "queue"
 * semantics (hermes-agent parity) — a second message for a busy session is queued rather than
 * running concurrently against the same conversation.
 *
 * <p>Sessions are independent: different {@code session_id}s run in parallel. Tasks are expected to
 * kick off async work and call {@link #onComplete(String)} exactly once when that work finishes
 * (success, failure, or stop), which drains the next queued task for the session.
 */
@Component
public class SessionRunQueue {

    private final Object lock = new Object();
    private final Set<String> active = new HashSet<>();
    private final Map<String, Deque<Runnable>> pending = new HashMap<>();

    /**
     * Submit a task for a session. Runs immediately if the session is idle; otherwise enqueues it.
     *
     * @return true if the task started immediately, false if it was queued
     */
    public boolean submit(String sessionId, Runnable task) {
        synchronized (lock) {
            if (!active.contains(sessionId)) {
                active.add(sessionId);
                // run() only kicks off async work and returns; safe under lock
                task.run();
                return true;
            }
            pending.computeIfAbsent(sessionId, k -> new ArrayDeque<>()).addLast(task);
            return false;
        }
    }

    /** Signal that the active task for a session finished; starts the next queued task, if any. */
    public void onComplete(String sessionId) {
        synchronized (lock) {
            Deque<Runnable> q = pending.get(sessionId);
            if (q != null && !q.isEmpty()) {
                Runnable next = q.pollFirst();
                if (q.isEmpty()) pending.remove(sessionId);
                next.run();
            } else {
                active.remove(sessionId);
            }
        }
    }

    /** Number of tasks waiting (not counting the active one) for a session. */
    public int queuedCount(String sessionId) {
        synchronized (lock) {
            Deque<Runnable> q = pending.get(sessionId);
            return q == null ? 0 : q.size();
        }
    }

    public boolean isActive(String sessionId) {
        synchronized (lock) {
            return active.contains(sessionId);
        }
    }
}
