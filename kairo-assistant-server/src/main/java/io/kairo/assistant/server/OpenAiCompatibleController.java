package io.kairo.assistant.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.core.session.ConcurrencyLimitExceededException;
import io.kairo.assistant.gateway.ModelRegistry;
import io.kairo.assistant.gateway.ModelSwitchService;
import io.kairo.core.session.SessionKey;
import io.kairo.core.session.UnifiedGateway;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/v1")
public class OpenAiCompatibleController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleController.class);
    private static final String MODEL_ID = "kairo-assistant";

    private final UnifiedGateway gateway;
    private final SessionAwareDeltaRouter sessionDeltaRouter;
    private final ModelSwitchService modelSwitchService;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleController(UnifiedGateway gateway,
                                      SessionAwareDeltaRouter sessionDeltaRouter,
                                      ModelSwitchService modelSwitchService) {
        this.gateway = gateway;
        this.sessionDeltaRouter = sessionDeltaRouter;
        this.modelSwitchService = modelSwitchService;
    }

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/chat/completions")
    public ResponseEntity<?> chatCompletions(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        if (messages == null || messages.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse("messages is required"));
        }

        Map<String, Object> lastMsg = messages.get(messages.size() - 1);
        String content = extractContent(lastMsg);
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(errorResponse("last message content is empty"));
        }

        String clientId = sessionId != null ? sessionId
                : UUID.randomUUID().toString().substring(0, 12);
        SessionKey key = SessionKey.of("openai-api", clientId);

        if (content.startsWith("/model")) {
            String modelId = content.substring("/model".length()).trim();
            return handleModelSwitch(key, modelId);
        }

        String requestModel = (String) request.get("model");
        if (requestModel != null && !MODEL_ID.equals(requestModel)) {
            ModelSwitchService.SwitchResult result = modelSwitchService.switchModel(key, requestModel);
            if (!result.success()) {
                log.warn("Model switch request failed for [{}]: {}", key, result.message());
            }
        }

        if (Boolean.TRUE.equals(request.get("stream"))) {
            Flux<String> flux = streamResponse(key, content, clientId);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(flux);
        }

        return blockingResponse(key, content, clientId).block();
    }


    @GetMapping("/models")
    public Map<String, Object> listModels() {
        List<Map<String, Object>> models = new java.util.ArrayList<>();
        models.add(Map.of(
                "id", MODEL_ID,
                "object", "model",
                "created", Instant.now().getEpochSecond(),
                "owned_by", "kairo"));
        for (var entry : modelSwitchService.registry().all().entrySet()) {
            ModelRegistry.ModelSpec spec = entry.getValue();
            models.add(Map.of(
                    "id", spec.modelName(),
                    "object", "model",
                    "created", Instant.now().getEpochSecond(),
                    "owned_by", spec.provider()));
        }
        return Map.of("object", "list", "data", models);
    }

    @GetMapping("/runs")
    public Map<String, Object> listRuns() {
        return Map.of(
                "active", gateway.activeRequestCount(),
                "pool_size", gateway.pool().size(),
                "draining", gateway.isDraining());
    }

    private ResponseEntity<?> handleModelSwitch(SessionKey key, String modelId) {
        if (modelId.isEmpty()) {
            String available = String.join(", ", modelSwitchService.registry().aliases());
            return ResponseEntity.ok().body(Map.of(
                    "message", "Available models: " + available));
        }
        ModelSwitchService.SwitchResult result = modelSwitchService.switchModel(key, modelId);
        if (result.success()) {
            return ResponseEntity.ok().body(Map.of("message", result.message()));
        }
        return ResponseEntity.badRequest().body(errorResponse(result.message()));
    }

    private Mono<ResponseEntity<Map<String, Object>>> blockingResponse(
            SessionKey key, String content, String clientId) {
        Msg input = Msg.of(MsgRole.USER, content);
        return gateway.route(key, input)
                .map(response -> {
                    String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                    Map<String, Object> body = Map.of(
                            "id", id,
                            "object", "chat.completion",
                            "created", Instant.now().getEpochSecond(),
                            "model", MODEL_ID,
                            "choices", List.of(Map.of(
                                    "index", 0,
                                    "message", Map.of(
                                            "role", "assistant",
                                            "content", response.text()),
                                    "finish_reason", "stop")),
                            "usage", Map.of(
                                    "prompt_tokens", 0,
                                    "completion_tokens", 0,
                                    "total_tokens", 0));
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body);
                })
                .onErrorResume(e -> {
                    if (e instanceof ConcurrencyLimitExceededException) {
                        return Mono.just(ResponseEntity.status(429)
                                .body(rateLimitResponse(e.getMessage())));
                    }
                    log.error("OpenAI API error", e);
                    return Mono.just(ResponseEntity.internalServerError()
                            .body(errorResponse(e.getMessage())));
                });
    }

    private Flux<String> streamResponse(SessionKey key, String content, String clientId) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String subId = "openai-stream-" + clientId + "-" + System.nanoTime();

        sessionDeltaRouter.subscribe(key, subId, delta -> {
            var choice = new HashMap<String, Object>();
            choice.put("index", 0);
            choice.put("delta", Map.of("content", delta));
            choice.put("finish_reason", null);
            Map<String, Object> chunk = Map.of(
                    "id", completionId,
                    "object", "chat.completion.chunk",
                    "created", Instant.now().getEpochSecond(),
                    "model", MODEL_ID,
                    "choices", List.of(choice));
            emitChunk(sink, chunk);
        });

        Msg input = Msg.of(MsgRole.USER, content);
        Disposable routeSub = gateway.route(key, input)
                .doOnSuccess(response -> {
                    sessionDeltaRouter.unsubscribe(key, subId);
                    Map<String, Object> finalChunk = Map.of(
                            "id", completionId,
                            "object", "chat.completion.chunk",
                            "created", Instant.now().getEpochSecond(),
                            "model", MODEL_ID,
                            "choices", List.of(Map.of(
                                    "index", 0,
                                    "delta", Map.of(),
                                    "finish_reason", "stop")));
                    emitChunk(sink, finalChunk);
                    sink.tryEmitNext("data: [DONE]\n\n");
                    sink.tryEmitComplete();
                })
                .doOnError(e -> {
                    sessionDeltaRouter.unsubscribe(key, subId);
                    log.error("OpenAI streaming error", e);
                    sink.tryEmitNext("data: [DONE]\n\n");
                    sink.tryEmitComplete();
                })
                .subscribe();

        // Stop the agent run if the API client disconnects mid-stream.
        return sink.asFlux().doOnCancel(() -> {
            sessionDeltaRouter.unsubscribe(key, subId);
            gateway.interrupt(key);
            routeSub.dispose();
        });
    }

    private void emitChunk(Sinks.Many<String> sink, Map<String, Object> chunk) {
        try {
            sink.tryEmitNext("data: " + mapper.writeValueAsString(chunk) + "\n\n");
        } catch (Exception e) {
            log.debug("Failed to serialize chunk: {}", e.getMessage());
        }
    }

    private String extractContent(Map<String, Object> message) {
        Object content = message.get("content");
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> m) {
                    if ("text".equals(m.get("type"))) {
                        sb.append(m.get("text"));
                    }
                }
            }
            return sb.toString();
        }
        return null;
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> errorResponse(String message) {
        return Map.of("error", Map.of(
                "message", message,
                "type", "invalid_request_error",
                "code", "invalid_request"));
    }

    private Map<String, Object> rateLimitResponse(String message) {
        return Map.of("error", Map.of(
                "message", message,
                "type", "rate_limit_exceeded",
                "code", "rate_limit_exceeded"));
    }
}
