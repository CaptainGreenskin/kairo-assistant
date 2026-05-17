package io.kairo.assistant.server;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.core.agent.DefaultReActAgent;
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

    private final AssistantSession session;
    private final SessionManager sessionManager;
    private final AssistantWebSocketHandler wsHandler;

    public ChatController(
            AssistantSession session,
            SessionManager sessionManager,
            AssistantWebSocketHandler wsHandler) {
        this.session = session;
        this.sessionManager = sessionManager;
        this.wsHandler = wsHandler;
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

        java.util.function.Consumer<String> prevConsumer = captureCurrentConsumer();

        if (session.agent() instanceof DefaultReActAgent agent) {
            agent.setTextDeltaConsumer(delta -> {
                String escaped = delta.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r");
                sink.tryEmitNext("data: {\"type\":\"delta\",\"content\":\"" + escaped + "\"}\n\n");
                if (prevConsumer != null) prevConsumer.accept(delta);
            });
        }

        Msg input = Msg.of(MsgRole.USER, request.message());
        session.agent().call(input)
                .doOnSuccess(response -> {
                    if (response != null) {
                        String escaped = response.text().replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r");
                        sink.tryEmitNext("data: {\"type\":\"response\",\"content\":\"" + escaped + "\"}\n\n");
                    }
                    sink.tryEmitNext("data: {\"type\":\"done\"}\n\n");
                    sink.tryEmitComplete();
                    restoreConsumer(prevConsumer);
                })
                .doOnError(e -> {
                    sink.tryEmitNext("data: {\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}\n\n");
                    sink.tryEmitComplete();
                    restoreConsumer(prevConsumer);
                })
                .subscribe();

        return sink.asFlux();
    }

    @PostMapping(value = "/chat/interrupt")
    public Map<String, String> interrupt() {
        session.agent().interrupt();
        return Map.of("status", "interrupted");
    }

    private java.util.function.Consumer<String> captureCurrentConsumer() {
        return wsHandler != null ? wsHandler.broadcastConsumer() : null;
    }

    private void restoreConsumer(java.util.function.Consumer<String> consumer) {
        if (session.agent() instanceof DefaultReActAgent agent) {
            java.util.function.Consumer<String> restore = consumer;
            if (restore == null && wsHandler != null) restore = wsHandler.broadcastConsumer();
            agent.setTextDeltaConsumer(restore);
        }
    }

    public record ChatRequest(String message) {}
}
