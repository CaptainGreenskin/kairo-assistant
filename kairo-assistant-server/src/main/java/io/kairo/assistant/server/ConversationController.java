package io.kairo.assistant.server;

import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.ConversationStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationStore store;

    public ConversationController(AssistantSession session) {
        Path dataDir = Path.of(session.config().dataDir());
        this.store = new ConversationStore(dataDir.resolve("conversations").resolve("web"));
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, String>> sessions = store.listSessions();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", sessions.size());
        result.put("conversations", sessions);
        return result;
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> get(@PathVariable String sessionId) {
        List<Map<String, Object>> entries = store.loadSession(sessionId);
        if (entries.isEmpty()) {
            return Map.of("error", "conversation not found", "sessionId", sessionId);
        }
        List<Map<String, Object>> messages = entries.stream()
                .filter(e -> "message".equals(e.get("type")))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        String title = store.getTitle(sessionId);
        if (title != null) result.put("title", title);
        result.put("messageCount", messages.size());
        result.put("messages", messages);
        return result;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q) {
        if (q == null || q.isBlank()) {
            return Map.of("error", "query parameter 'q' is required");
        }
        List<Map<String, Object>> results = store.search(q);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", q);
        response.put("total", results.size());
        response.put("results", results);
        return response;
    }

    @GetMapping(value = "/{sessionId}/export", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> export(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "markdown") String format) {
        String exported = store.exportSession(sessionId, format);
        if (exported == null) {
            return ResponseEntity.notFound().build();
        }
        String contentType = "json".equalsIgnoreCase(format)
                ? MediaType.APPLICATION_JSON_VALUE
                : MediaType.TEXT_MARKDOWN_VALUE;
        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .body(exported);
    }

    @PutMapping("/{sessionId}/title")
    public Map<String, Object> setTitle(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return Map.of("error", "title is required");
        }
        boolean ok = store.setTitle(sessionId, title);
        if (!ok) {
            return Map.of("error", "conversation not found", "sessionId", sessionId);
        }
        return Map.of("status", "updated", "sessionId", sessionId, "title", title);
    }

    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable String sessionId) {
        boolean deleted = store.deleteSession(sessionId);
        if (!deleted) {
            return Map.of("error", "conversation not found", "sessionId", sessionId);
        }
        return Map.of("status", "deleted", "sessionId", sessionId);
    }
}
