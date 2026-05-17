package io.kairo.assistant.server;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.core.agent.DefaultReActAgent;
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
    private final ConcurrentHashMap<String, Sinks.Many<String>> connections = new ConcurrentHashMap<>();

    public SseController(AssistantSession session) {
        this.session = session;
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

        if (session.agent() instanceof DefaultReActAgent agent) {
            agent.setTextDeltaConsumer(delta -> {
                String escaped = escapeJson(delta);
                sink.tryEmitNext(sseEvent("delta", Map.of("content", escaped)));
            });
        }

        Msg input = Msg.of(MsgRole.USER, message);
        session.agent().call(input)
                .doOnSuccess(response -> {
                    if (response != null) {
                        sink.tryEmitNext(sseEvent("response", Map.of("content", escapeJson(response.text()))));
                    }
                    sink.tryEmitNext(sseEvent("done", Map.of()));
                    if (session.agent() instanceof DefaultReActAgent agent) {
                        agent.setTextDeltaConsumer(null);
                    }
                })
                .doOnError(e -> {
                    sink.tryEmitNext(sseEvent("error", Map.of("message", escapeJson(e.getMessage()))));
                    if (session.agent() instanceof DefaultReActAgent agent) {
                        agent.setTextDeltaConsumer(null);
                    }
                })
                .subscribe();

        return Map.of("status", "processing");
    }

    @PostMapping("/interrupt")
    public Map<String, String> interrupt(@RequestParam(defaultValue = "default") String clientId) {
        session.agent().interrupt();
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

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
