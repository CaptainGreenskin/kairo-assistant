/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.ConversationStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hermes Replay — redacted, shareable session exports.
 *
 * <p>{@code GET /api/replay/{sessionId}?format=json|markdown|html}
 *
 * <p>The pipeline:
 *
 * <ol>
 *   <li>Load raw entries from {@link ConversationStore}.
 *   <li>Run every message body through {@link SessionRedactor} (scrubs API keys, emails,
 *       absolute paths, JWTs, UUIDs).
 *   <li>Render to the requested format. JSON keeps the entry array shape; Markdown matches the
 *       existing {@code ConversationStore.exportSession} shape; HTML embeds inline CSS so the
 *       file can be opened standalone.
 * </ol>
 *
 * <p>The redactor is intentionally aggressive — "Safe Share Mode" by default. Operators who need
 * the raw bytes already have it on disk under {@code ~/.kairo/assistant/conversations/web/}.
 */
@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    private final ConversationStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReplayController(AssistantSession session) {
        Path dataDir = Path.of(session.config().dataDir());
        this.store = new ConversationStore(dataDir.resolve("conversations").resolve("web"));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<String> export(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "json") String format) {
        List<Map<String, Object>> entries = store.loadSession(sessionId);
        if (entries.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("session not found: " + sessionId);
        }
        List<Map<String, Object>> redacted = redactEntries(entries);

        try {
            return switch (format.toLowerCase(Locale.ROOT)) {
                case "json" -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(redacted));
                case "markdown", "md" -> ResponseEntity.ok()
                        .header("Content-Type", "text/markdown; charset=utf-8")
                        .body(renderMarkdown(sessionId, redacted));
                case "html" -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(renderHtml(sessionId, redacted));
                default -> ResponseEntity.badRequest()
                        .body("unknown format: " + format + " (use json|markdown|html)");
            };
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("render failed: " + e.getMessage());
        }
    }

    /** Returns the same JSON the file exporter would produce, for preview in the UI. */
    @GetMapping("/{sessionId}/preview")
    public Map<String, Object> preview(@PathVariable String sessionId) {
        List<Map<String, Object>> entries = store.loadSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (entries.isEmpty()) {
            result.put("error", "session not found");
            return result;
        }
        result.put("sessionId", sessionId);
        result.put("title", store.getTitle(sessionId));
        result.put("entries", redactEntries(entries));
        result.put("generatedAt", Instant.now().toString());
        result.put("note", "Redactor scrubs api-keys, emails, absolute paths, JWTs, UUIDs.");
        return result;
    }

    // ----- internals -----

    private static List<Map<String, Object>> redactEntries(List<Map<String, Object>> entries) {
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (Map<String, Object> entry : entries) {
            Map<String, Object> scrubbed = new LinkedHashMap<>(entry);
            Object content = scrubbed.get("content");
            if (content instanceof String s) {
                scrubbed.put("content", SessionRedactor.redact(s));
            }
            out.add(scrubbed);
        }
        return out;
    }

    private static String renderMarkdown(String sessionId, List<Map<String, Object>> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Replay: ").append(sessionId).append("\n\n");
        sb.append("_Exported at ").append(Instant.now()).append(" — Safe Share Mode_\n\n");
        sb.append("---\n\n");
        for (Map<String, Object> entry : entries) {
            String type = String.valueOf(entry.getOrDefault("type", ""));
            if (!"message".equals(type)) continue;
            String role = String.valueOf(entry.getOrDefault("role", "?"));
            String content = String.valueOf(entry.getOrDefault("content", ""));
            String ts = entry.get("timestamp") == null ? "" : entry.get("timestamp").toString();
            sb.append("**").append(role).append("** _").append(ts).append("_\n\n");
            sb.append(content).append("\n\n---\n\n");
        }
        return sb.toString();
    }

    private static String renderHtml(String sessionId, List<Map<String, Object>> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<title>Replay · ").append(escapeHtml(sessionId)).append("</title>");
        sb.append("<style>")
                .append("body{font-family:-apple-system,system-ui,sans-serif;max-width:840px;")
                .append("margin:2em auto;padding:0 1em;background:#0e1726;color:#e5e7eb;line-height:1.55}")
                .append("h1{color:#2dd4bf;border-bottom:1px solid #233556;padding-bottom:.3em}")
                .append(".msg{margin:1.2em 0;padding:.8em 1em;border-left:3px solid #233556;background:#122236;border-radius:6px}")
                .append(".msg.user{border-left-color:#2dd4bf}.msg.assistant{border-left-color:#a78bfa}")
                .append(".role{font-size:.7em;letter-spacing:.1em;text-transform:uppercase;color:#94a3b8;font-weight:600}")
                .append(".ts{font-size:.7em;color:#94a3b8;margin-left:1em;font-family:ui-monospace,monospace}")
                .append(".content{margin-top:.5em;white-space:pre-wrap;word-wrap:break-word}")
                .append("footer{margin-top:3em;color:#94a3b8;font-size:.8em;border-top:1px solid #233556;padding-top:1em}")
                .append("</style></head><body>");
        sb.append("<h1>☤ Replay</h1>");
        sb.append("<div class=\"ts\">session ").append(escapeHtml(sessionId)).append("</div>");
        for (Map<String, Object> entry : entries) {
            String type = String.valueOf(entry.getOrDefault("type", ""));
            if (!"message".equals(type)) continue;
            String role = String.valueOf(entry.getOrDefault("role", "?"));
            String content = String.valueOf(entry.getOrDefault("content", ""));
            String ts = entry.get("timestamp") == null ? "" : entry.get("timestamp").toString();
            sb.append("<div class=\"msg ").append(escapeHtml(role)).append("\">");
            sb.append("<span class=\"role\">").append(escapeHtml(role)).append("</span>");
            sb.append("<span class=\"ts\">").append(escapeHtml(ts)).append("</span>");
            sb.append("<div class=\"content\">").append(escapeHtml(content)).append("</div>");
            sb.append("</div>");
        }
        sb.append("<footer>Generated by Kairo Replay · Safe Share Mode (api keys, emails, paths, JWTs, UUIDs redacted) · ")
                .append(Instant.now()).append("</footer>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
