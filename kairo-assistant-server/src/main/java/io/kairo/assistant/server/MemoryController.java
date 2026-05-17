package io.kairo.assistant.server;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.assistant.agent.AssistantSession;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final AssistantSession session;
    private final EventBroadcaster broadcaster;

    public MemoryController(AssistantSession session, EventBroadcaster broadcaster) {
        this.session = session;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @RequestParam(defaultValue = "GLOBAL") String scope) {
        MemoryScope memScope = parseScope(scope);
        return session.memoryStore().list(memScope)
                .map(this::toMap)
                .collectList()
                .map(entries -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("scope", memScope.name());
                    result.put("total", entries.size());
                    result.put("entries", entries);
                    return result;
                });
    }

    @GetMapping("/search")
    public Mono<Map<String, Object>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "GLOBAL") String scope) {
        MemoryScope memScope = parseScope(scope);
        return session.memoryStore().search(q, memScope)
                .map(this::toMap)
                .collectList()
                .map(entries -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("query", q);
                    result.put("scope", memScope.name());
                    result.put("total", entries.size());
                    result.put("entries", entries);
                    return result;
                });
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> get(@PathVariable String id) {
        return session.memoryStore().get(id)
                .map(this::toMap)
                .defaultIfEmpty(Map.of("error", "not found", "id", id));
    }

    @PostMapping
    public Mono<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        String content = String.valueOf(body.getOrDefault("content", ""));
        if (content.isBlank()) {
            return Mono.just(Map.of("error", "content is required"));
        }
        String id = String.valueOf(body.getOrDefault("id", UUID.randomUUID().toString()));
        String scopeStr = String.valueOf(body.getOrDefault("scope", "GLOBAL"));
        double importance = Double.parseDouble(String.valueOf(body.getOrDefault("importance", "0.5")));

        @SuppressWarnings("unchecked")
        List<String> tagList = body.get("tags") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();

        MemoryEntry entry = new MemoryEntry(
                id, null, content, null,
                parseScope(scopeStr), importance,
                null, Set.copyOf(tagList), Instant.now(), Map.of());

        return session.memoryStore().save(entry)
                .map(saved -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "saved");
                    result.put("id", saved.id());
                    broadcaster.broadcast(Map.of("type", "memory_saved", "id", saved.id(), "content", content));
                    return result;
                });
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable String id) {
        return session.memoryStore().delete(id)
                .doOnSuccess(v -> broadcaster.broadcast(Map.of("type", "memory_deleted", "id", id)))
                .thenReturn(Map.<String, Object>of("status", "deleted", "id", id));
    }

    private MemoryScope parseScope(String scope) {
        try {
            return MemoryScope.valueOf(scope.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemoryScope.GLOBAL;
        }
    }

    private Map<String, Object> toMap(MemoryEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("content", e.content());
        m.put("scope", e.scope().name());
        m.put("importance", e.importance());
        m.put("tags", List.copyOf(e.tags()));
        if (e.timestamp() != null) m.put("timestamp", e.timestamp().toString());
        if (e.agentId() != null) m.put("agentId", e.agentId());
        return m;
    }
}
