package io.kairo.assistant.server;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantSession;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final int MAX_TITLE_LENGTH = 50;

    private final AssistantSession session;
    private final SessionManager sessionManager;
    private final StreamingDeltaRouter deltaRouter;

    public ChatController(
            AssistantSession session,
            SessionManager sessionManager,
            StreamingDeltaRouter deltaRouter) {
        this.session = session;
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

        Msg input = Msg.of(MsgRole.USER, request.message());
        return session.agent().call(input)
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

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            return Flux.just("data: {\"type\":\"error\",\"message\":\"message is required\"}\n\n");
        }

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        String subId = "chat-stream-" + UUID.randomUUID().toString().substring(0, 8);

        deltaRouter.subscribe(subId, delta ->
                sink.tryEmitNext("data: {\"type\":\"delta\",\"content\":\"" + JsonEscape.escape(delta) + "\"}\n\n"));

        Msg input = Msg.of(MsgRole.USER, request.message());
        session.agent().call(input)
                .doOnSuccess(response -> {
                    deltaRouter.unsubscribe(subId);
                    if (response != null) {
                        sink.tryEmitNext("data: {\"type\":\"response\",\"content\":\"" + JsonEscape.escape(response.text()) + "\"}\n\n");
                    }
                    sink.tryEmitNext("data: {\"type\":\"done\"}\n\n");
                    sink.tryEmitComplete();
                })
                .doOnError(e -> {
                    deltaRouter.unsubscribe(subId);
                    String errMsg = e.getMessage() != null ? e.getMessage() : "unknown error";
                    sink.tryEmitNext("data: {\"type\":\"error\",\"message\":\"" + JsonEscape.escape(errMsg) + "\"}\n\n");
                    sink.tryEmitComplete();
                })
                .subscribe();

        return sink.asFlux();
    }

    @PostMapping(value = "/chat/interrupt")
    public Map<String, String> interrupt() {
        session.agent().interrupt();
        return Map.of("status", "interrupted");
    }

    public record ChatRequest(String message) {}
}
