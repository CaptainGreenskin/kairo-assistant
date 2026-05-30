package io.kairo.assistant.server;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.core.session.SessionKey;
import io.kairo.core.session.UnifiedGateway;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final int MAX_TITLE_LENGTH = 50;

    // Total wall-clock cap on a single chat turn (LLM call + any tool loops).
    // If the upstream model hangs (network stall, broken endpoint), the stream
    // emits an error event and closes, so the UI clears its "●…" loader
    // instead of waiting forever. Override via env if the model legitimately
    // takes longer (e.g. very large outputs or long tool chains).
    private static final Duration CHAT_STREAM_TIMEOUT = Duration.ofSeconds(
            parsePositiveLong(System.getenv("KAIRO_CHAT_STREAM_TIMEOUT_SECONDS"), 300L));

    private static long parsePositiveLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private final AssistantSession session;
    private final UnifiedGateway gateway;
    private final SessionAwareDeltaRouter sessionDeltaRouter;
    private final SessionManager sessionManager;
    private final StreamingDeltaRouter deltaRouter;

    public ChatController(
            AssistantSession session,
            UnifiedGateway gateway,
            SessionAwareDeltaRouter sessionDeltaRouter,
            SessionManager sessionManager,
            StreamingDeltaRouter deltaRouter) {
        this.session = session;
        this.gateway = gateway;
        this.sessionDeltaRouter = sessionDeltaRouter;
        this.sessionManager = sessionManager;
        this.deltaRouter = deltaRouter;
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (request.message() == null || request.message().isBlank()) {
            return Mono.just(Map.of("error", "message is required"));
        }

        String clientId = sessionId != null ? sessionId : UUID.randomUUID().toString().substring(0, 8);
        var clientSession = sessionManager.getOrCreate(clientId);
        clientSession.conversationStore().appendMessage("user", request.message());
        int msgNum = clientSession.incrementMessages();

        if (msgNum == 1) {
            String title = request.message().length() > MAX_TITLE_LENGTH
                    ? request.message().substring(0, MAX_TITLE_LENGTH - 3) + "..." : request.message();
            title = title.replaceAll("[\\r\\n]+", " ").trim();
            clientSession.conversationStore().setTitle(
                    clientSession.conversationStore().currentSessionId(), title);
        }

        SessionKey key = SessionKey.of("chat", clientId);
        Msg input = Msg.of(MsgRole.USER, request.message());
        return gateway.route(key, input)
                .map(response -> {
                    clientSession.conversationStore().appendMessage("assistant", response.text());
                    return Map.<String, Object>of(
                            "response", response.text(),
                            "role", response.role().name(),
                            "sessionId", clientId);
                })
                .onErrorResume(e -> Mono.just(Map.of(
                        "error", e.getMessage())));
    }

    // Spring's SSE encoder frames each Flux<String> element as "data:<value>\n\n"
    // itself, so emit only the JSON payload here — don't pre-frame. Explicit
    // UTF-8 charset is required: text/event-stream in servlet stack defaults to
    // ISO-8859-1, which mangles non-ASCII (e.g. Chinese) content.
    @PostMapping(value = "/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<String> chatStream(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (request.message() == null || request.message().isBlank()) {
            return Flux.just("{\"type\":\"error\",\"message\":\"message is required\"}");
        }

        String clientId = sessionId != null ? sessionId : UUID.randomUUID().toString().substring(0, 8);
        SessionKey key = SessionKey.of("chat", clientId);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        String subId = "chat-stream-" + clientId + "-" + System.nanoTime();

        sessionDeltaRouter.subscribe(key, subId, delta ->
                sink.tryEmitNext("{\"type\":\"delta\",\"content\":\"" + JsonEscape.escape(delta) + "\"}"));

        Msg input = Msg.of(MsgRole.USER, request.message());
        Disposable routeSub = gateway.route(key, input)
                .timeout(CHAT_STREAM_TIMEOUT)
                .doOnSuccess(response -> {
                    sessionDeltaRouter.unsubscribe(key, subId);
                    if (response != null) {
                        sink.tryEmitNext("{\"type\":\"response\",\"content\":\"" + JsonEscape.escape(response.text()) + "\"}");
                    }
                    sink.tryEmitNext("{\"type\":\"done\"}");
                    sink.tryEmitComplete();
                })
                .doOnError(e -> {
                    sessionDeltaRouter.unsubscribe(key, subId);
                    String errMsg;
                    if (e instanceof TimeoutException) {
                        errMsg = "Upstream model did not respond within "
                                + CHAT_STREAM_TIMEOUT.toSeconds() + "s";
                    } else {
                        errMsg = e.getMessage() != null
                                ? e.getClass().getSimpleName() + ": " + e.getMessage()
                                : e.getClass().getSimpleName();
                    }
                    sink.tryEmitNext("{\"type\":\"error\",\"message\":\"" + JsonEscape.escape(errMsg) + "\"}");
                    sink.tryEmitComplete();
                })
                .subscribe();

        // If the HTTP client disconnects, stop the agent run instead of letting
        // it stream into a dead sink until the timeout fires.
        return sink.asFlux().doOnCancel(() -> {
            sessionDeltaRouter.unsubscribe(key, subId);
            gateway.interrupt(key);
            routeSub.dispose();
        });
    }

    @PostMapping(value = "/chat/interrupt")
    public Map<String, String> interrupt(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String clientId = sessionId != null ? sessionId : "default";
        gateway.interrupt(SessionKey.of("chat", clientId));
        return Map.of("status", "interrupted");
    }

    public record ChatRequest(String message) {}
}
