/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.server;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.ConversationStore;
import io.kairo.core.session.SessionKey;
import io.kairo.core.session.UnifiedGateway;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Asynchronous Runs API — launch an agent run that outlives the HTTP request, then poll or stream
 * its lifecycle. Parity with hermes-agent's structured runs.
 *
 * <ul>
 *   <li>{@code POST /v1/runs} {input} → {@code 202 {run_id, status}} — fire-and-track.
 *   <li>{@code GET /v1/runs/{id}} → status snapshot (+ result/error when finished).
 *   <li>{@code GET /v1/runs/{id}/events} → SSE lifecycle stream (created/running/delta/…/terminal).
 *   <li>{@code POST /v1/runs/{id}/stop} → interrupt + mark stopped.
 * </ul>
 *
 * <p>Approval-gated runs ({@code POST /v1/runs/{id}/approval}) are intentionally out of this slice:
 * they require approval-gating in the agent loop (framework-level) and will be added separately.
 */
@RestController
@RequestMapping("/v1/runs")
public class RunController {

    private final UnifiedGateway gateway;
    private final SessionAwareDeltaRouter deltaRouter;
    private final RunRegistry registry;
    private final SessionRunQueue queue;
    private final ConversationStore conversations;

    public RunController(UnifiedGateway gateway, SessionAwareDeltaRouter deltaRouter,
                         RunRegistry registry, SessionRunQueue queue, AssistantSession session) {
        this.gateway = gateway;
        this.deltaRouter = deltaRouter;
        this.registry = registry;
        this.queue = queue;
        Path dataDir = Path.of(session.config().dataDir());
        this.conversations = new ConversationStore(dataDir.resolve("conversations").resolve("web"));
    }

    /** {@code sessionId} (optional): runs sharing one session are serialized — second is queued. */
    public record RunRequest(String input, String sessionId) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody RunRequest request) {
        if (request == null || request.input() == null || request.input().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "input is required"));
        }

        String runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String sessionId = request.sessionId();
        boolean scoped = sessionId != null && !sessionId.isBlank();
        SessionKey key = SessionKey.of("run", scoped ? sessionId : runId);
        registry.create(runId, key);

        String input = request.input();
        if (scoped) {
            boolean started = queue.submit(sessionId, () -> launchRun(runId, key, input, sessionId));
            if (!started) registry.markQueued(runId);
            return ResponseEntity.accepted().body(Map.of(
                    "run_id", runId, "status", started ? "running" : "queued", "session_id", sessionId));
        }
        launchRun(runId, key, input, null);
        return ResponseEntity.accepted().body(Map.of("run_id", runId, "status", "running"));
    }

    /** Starts the detached agent run. When {@code sessionId != null}, drains the queue on completion. */
    private void launchRun(String runId, SessionKey key, String input, String sessionId) {
        RunRegistry.Run run = registry.get(runId);
        if (run == null || run.status() == RunRegistry.Status.STOPPED) {
            // Cancelled while still queued — skip and let the next queued run proceed.
            if (sessionId != null) queue.onComplete(sessionId);
            return;
        }
        String subId = "run-" + runId;
        deltaRouter.subscribe(key, subId, delta ->
                registry.emit(runId, "run.delta", Map.of("content", delta)));

        Disposable disposable = gateway.route(key, Msg.of(MsgRole.USER, input))
                .doOnSubscribe(s -> registry.markRunning(runId))
                .doFinally(sig -> {
                    deltaRouter.unsubscribe(key, subId);
                    if (sessionId != null) queue.onComplete(sessionId);
                })
                .subscribe(
                        response -> registry.succeed(runId, response == null ? "" : response.text()),
                        error -> registry.fail(runId,
                                error.getMessage() == null ? error.toString() : error.getMessage()));
        registry.attachDisposable(runId, disposable);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        RunRegistry.Run run = registry.get(id);
        if (run == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "run not found", "run_id", id));
        }
        return ResponseEntity.ok(registry.snapshot(run));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<String> events(@PathVariable String id) {
        Flux<String> stream = registry.events(id);
        if (stream == null) {
            return Flux.just("data: {\"type\":\"run.error\",\"error\":\"run not found\"}\n\n");
        }
        return stream;
    }

    public record HandoffRequest(String sourceSessionId, String sessionId) {}

    /**
     * Hand off an existing conversation to a fresh agent session: loads the source transcript and
     * seeds a (new or given) run session's agent with it, so the conversation continues — e.g. via
     * {@code POST /v1/runs {input, sessionId}}. Returns the target {@code session_id}.
     */
    @PostMapping("/handoff")
    public ResponseEntity<Map<String, Object>> handoff(@RequestBody HandoffRequest request) {
        if (request == null || request.sourceSessionId() == null
                || request.sourceSessionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sourceSessionId is required"));
        }
        List<Map<String, Object>> entries = conversations.loadSession(request.sourceSessionId());
        if (entries.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "source session not found", "sourceSessionId",
                            request.sourceSessionId()));
        }
        List<Msg> history = toMessages(entries);
        if (history.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "source session has no replayable messages"));
        }

        String targetSessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId()
                : "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        SessionKey key = SessionKey.of("run", targetSessionId);
        Agent agent = gateway.pool().getOrCreate(key);
        agent.injectMessages(history);

        return ResponseEntity.ok(Map.of(
                "session_id", targetSessionId, "seeded", history.size()));
    }

    private static List<Msg> toMessages(List<Map<String, Object>> entries) {
        List<Msg> out = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (!"message".equals(e.get("type"))) continue;
            String role = String.valueOf(e.getOrDefault("role", ""));
            String content = e.get("content") == null ? "" : String.valueOf(e.get("content"));
            MsgRole msgRole = switch (role) {
                case "assistant" -> MsgRole.ASSISTANT;
                case "system" -> MsgRole.SYSTEM;
                default -> MsgRole.USER;
            };
            out.add(Msg.of(msgRole, content));
        }
        return out;
    }

    /** Steer a running run: inject a message into its agent's history, applied at the next iteration. */
    @PostMapping("/{id}/steer")
    public ResponseEntity<Map<String, Object>> steer(
            @PathVariable String id, @RequestBody RunRequest request) {
        RunRegistry.Run run = registry.get(id);
        if (run == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "run not found", "run_id", id));
        }
        if (request == null || request.input() == null || request.input().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "input is required"));
        }
        if (run.status() != RunRegistry.Status.RUNNING) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "run is not running", "status",
                            run.status().name().toLowerCase()));
        }
        Agent agent = gateway.pool().get(run.key());
        if (agent == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "no active agent for run", "run_id", id));
        }
        agent.injectMessages(List.of(Msg.of(MsgRole.USER, request.input())));
        registry.emit(id, "run.steered", Map.of("content", request.input()));
        return ResponseEntity.ok(Map.of("run_id", id, "status", "steered"));
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String id) {
        RunRegistry.Run run = registry.get(id);
        if (run == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "run not found", "run_id", id));
        }
        gateway.interrupt(run.key());
        boolean stopped = registry.stop(id);
        return ResponseEntity.ok(Map.of("run_id", id, "status", stopped ? "stopped" : "already_finished"));
    }
}
