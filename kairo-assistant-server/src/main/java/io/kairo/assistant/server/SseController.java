package io.kairo.assistant.server;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.gateway.SessionKey;
import io.kairo.assistant.gateway.UnifiedGateway;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@RestController
@RequestMapping("/api/sse")
public class SseController {

    private final AssistantSession session;
    private final UnifiedGateway gateway;
    private final SessionAwareDeltaRouter sessionDeltaRouter;
    private final StreamingDeltaRouter deltaRouter;
    private final ConcurrentHashMap<String, Sinks.Many<String>> connections = new ConcurrentHashMap<>();

    public SseController(AssistantSession session, UnifiedGateway gateway,
                         SessionAwareDeltaRouter sessionDeltaRouter,
                         StreamingDeltaRouter deltaRouter) {
        this.session = session;
        this.gateway = gateway;
        this.sessionDeltaRouter = sessionDeltaRouter;
        this.deltaRouter = deltaRouter;
    }

    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> connect(@RequestParam(defaultValue = "default") String clientId) {
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(256);
        connections.put(clientId, sink);

        sink.tryEmitNext(sseEvent("connected", Map.of("clientId", clientId)));

        return sink.asFlux()
                .doFinally(signal -> connections.remove(clientId));
    }

    @PostMapping("/send")
    public Map<String, Object> send(
            @RequestParam(defaultValue = "default") String clientId,
            @RequestBody Map<String, String> body) {

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return Map.of("error", "message is required");
        }

        Sinks.Many<String> sink = connections.get(clientId);
        if (sink == null) {
            return Map.of("error", "Not connected. Open /api/sse/connect?clientId=" + clientId + " first.");
        }

        sink.tryEmitNext(sseEvent("thinking", Map.of()));

        SessionKey key = SessionKey.of("sse", clientId);
        String subId = "sse-" + clientId + "-" + System.nanoTime();
        sessionDeltaRouter.subscribe(key, subId, delta -> {
            String escaped = JsonEscape.escape(delta);
            sink.tryEmitNext(sseEvent("delta", Map.of("content", escaped)));
        });

        Msg input = Msg.of(MsgRole.USER, message);
        gateway.route(key, input)
                .doOnSuccess(response -> {
                    sessionDeltaRouter.unsubscribe(key, subId);
                    if (response != null) {
                        sink.tryEmitNext(sseEvent("response", Map.of("content", JsonEscape.escape(response.text()))));
                    }
                    sink.tryEmitNext(sseEvent("done", Map.of()));
                })
                .doOnError(e -> {
                    sessionDeltaRouter.unsubscribe(key, subId);
                    sink.tryEmitNext(sseEvent("error", Map.of("message", JsonEscape.escape(e.getMessage()))));
                })
                .subscribe();

        return Map.of("status", "processing");
    }

    @PostMapping("/disconnect")
    public Map<String, String> disconnect(@RequestParam(defaultValue = "default") String clientId) {
        Sinks.Many<String> sink = connections.remove(clientId);
        if (sink != null) {
            sink.tryEmitNext(sseEvent("disconnected", Map.of()));
            sink.tryEmitComplete();
            return Map.of("status", "disconnected", "clientId", clientId);
        }
        return Map.of("status", "not_connected", "clientId", clientId);
    }

    @GetMapping("/connections")
    public Map<String, Object> listConnections() {
        return Map.of(
                "count", connections.size(),
                "clientIds", connections.keySet().stream().sorted().toList());
    }

    @PostMapping("/interrupt")
    public Map<String, String> interrupt(@RequestParam(defaultValue = "default") String clientId) {
        gateway.interrupt(SessionKey.of("sse", clientId));
        Sinks.Many<String> sink = connections.get(clientId);
        if (sink != null) {
            sink.tryEmitNext(sseEvent("interrupted", Map.of()));
        }
        return Map.of("status", "interrupted");
    }

    private String sseEvent(String type, Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"").append(type).append("\"");
        for (var entry : data.entrySet()) {
            sb.append(",\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
        }
        sb.append("}");
        return "data: " + sb + "\n\n";
    }

}
