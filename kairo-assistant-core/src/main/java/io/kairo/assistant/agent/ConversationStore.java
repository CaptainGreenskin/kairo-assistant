package io.kairo.assistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);
    private static final int PREVIEW_MAX_LENGTH = 60;
    private static final java.util.regex.Pattern SESSION_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Guards against path traversal — session ids must be simple slugs, never paths. */
    private static boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID.matcher(sessionId).matches();
    }

    private final Path baseDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private String currentSessionId;
    private Path currentFile;

    public ConversationStore(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.warn("Failed to create conversation directory: {}", baseDir, e);
        }
    }

    public synchronized String startSession() {
        currentSessionId = UUID.randomUUID().toString().substring(0, 8);
        currentFile = baseDir.resolve(currentSessionId + ".jsonl");
        appendEntry(Map.of(
                "type", "session_start",
                "sessionId", currentSessionId,
                "timestamp", Instant.now().toString()));
        return currentSessionId;
    }

    public synchronized void endSession() {
        if (currentFile == null) return;
        appendEntry(Map.of(
                "type", "session_end",
                "sessionId", currentSessionId != null ? currentSessionId : "",
                "timestamp", Instant.now().toString()));
    }

    public synchronized void appendMessage(String role, String content) {
        if (currentFile == null) startSession();
        appendEntry(Map.of(
                "type", "message",
                "role", role,
                "content", content,
                "timestamp", Instant.now().toString()));
    }

    public List<Map<String, Object>> loadSession(String sessionId) {
        if (!isValidSessionId(sessionId)) return List.of();
        Path file = baseDir.resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) return List.of();
        List<Map<String, Object>> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = mapper.readValue(line, Map.class);
                entries.add(entry);
            }
        } catch (IOException e) {
            log.error("Failed to load session: {}", sessionId, e);
        }
        return entries;
    }

    public List<Map<String, String>> listSessions() {
        List<Map<String, String>> sessions = new ArrayList<>();
        try (var stream = Files.list(baseDir)) {
            stream.filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(
                                    Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            log.debug("Failed to compare modification times: {}", e.getMessage());
                            return 0;
                        }
                    })
                    .forEach(p -> {
                        String id = p.getFileName().toString().replace(".jsonl", "");
                        String preview = getSessionPreview(p);
                        Map<String, String> info = new LinkedHashMap<>();
                        // Emit both `id` (legacy, kept for any external scripts)
                        // and `sessionId` (matches the conversation-detail and
                        // search endpoints — the console UI reads sessionId).
                        info.put("id", id);
                        info.put("sessionId", id);
                        info.put("preview", preview);
                        String title = getTitle(id);
                        if (title != null) {
                            info.put("title", title);
                        }
                        try {
                            info.put("lastModified",
                                    Files.getLastModifiedTime(p).toInstant().toString());
                        } catch (IOException e) {
                            log.debug("Failed to read lastModified for {}: {}", id, e.getMessage());
                        }
                        sessions.add(info);
                    });
        } catch (IOException e) {
            log.error("Failed to list sessions", e);
        }
        return sessions;
    }

    public synchronized String currentSessionId() {
        return currentSessionId;
    }

    public List<Map<String, Object>> search(String query) {
        return search(query, 50);
    }

    public List<Map<String, Object>> search(String query, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        try (var stream = Files.list(baseDir)) {
            stream.filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(
                                    Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .forEach(file -> {
                        if (results.size() >= limit) return;
                        String sessionId = file.getFileName().toString().replace(".jsonl", "");
                        String title = getTitle(sessionId);
                        // Title hit — surface even if no message body matches.
                        // Previously titles weren't searched at all (F20), so a
                        // session titled "用三句话介绍春天" wouldn't appear in
                        // search results for "春天" unless the same word also
                        // happened to occur inside a message body verbatim.
                        if (title != null && title.toLowerCase().contains(lowerQuery)) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("sessionId", sessionId);
                            result.put("sessionTitle", title);
                            result.put("matchedIn", "title");
                            result.put("snippet", extractSnippet(title, lowerQuery));
                            results.add(result);
                            if (results.size() >= limit) return;
                        }
                        try {
                            List<String> lines = Files.readAllLines(file);
                            List<Map<String, Object>> messages = new ArrayList<>();
                            for (String line : lines) {
                                if (line.isBlank()) continue;
                                @SuppressWarnings("unchecked")
                                Map<String, Object> entry = mapper.readValue(line, Map.class);
                                if ("message".equals(entry.get("type"))) {
                                    messages.add(entry);
                                }
                            }
                            for (int i = 0; i < messages.size() && results.size() < limit; i++) {
                                Map<String, Object> msg = messages.get(i);
                                String content = (String) msg.get("content");
                                if (content == null) continue;
                                if (content.toLowerCase().contains(lowerQuery)) {
                                    Map<String, Object> result = new LinkedHashMap<>();
                                    result.put("sessionId", sessionId);
                                    if (title != null) result.put("sessionTitle", title);
                                    result.put("role", msg.get("role"));
                                    result.put("timestamp", msg.get("timestamp"));
                                    result.put("snippet", extractSnippet(content, lowerQuery));
                                    result.put("messageIndex", i);

                                    List<Map<String, String>> context = new ArrayList<>();
                                    if (i > 0) {
                                        var prev = messages.get(i - 1);
                                        context.add(Map.of(
                                                "role", String.valueOf(prev.get("role")),
                                                "content", truncate(String.valueOf(prev.get("content")), 120)));
                                    }
                                    if (i < messages.size() - 1) {
                                        var next = messages.get(i + 1);
                                        context.add(Map.of(
                                                "role", String.valueOf(next.get("role")),
                                                "content", truncate(String.valueOf(next.get("content")), 120)));
                                    }
                                    if (!context.isEmpty()) {
                                        result.put("context", context);
                                    }
                                    results.add(result);
                                }
                            }
                        } catch (IOException e) {
                            log.debug("Failed to read session file during search: {}", e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to search conversations", e);
        }
        return results;
    }

    public List<Map<String, Object>> searchGrouped(String query, int limit) {
        List<Map<String, Object>> flat = search(query, limit * 3);
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (var result : flat) {
            String sid = (String) result.get("sessionId");
            grouped.computeIfAbsent(sid, k -> new ArrayList<>()).add(result);
        }
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            if (sessions.size() >= limit) break;
            Map<String, Object> sessionResult = new LinkedHashMap<>();
            sessionResult.put("sessionId", entry.getKey());
            var matches = entry.getValue();
            if (matches.get(0).containsKey("sessionTitle")) {
                sessionResult.put("title", matches.get(0).get("sessionTitle"));
            }
            sessionResult.put("matchCount", matches.size());
            sessionResult.put("matches", matches.stream().limit(3).toList());
            sessions.add(sessionResult);
        }
        return sessions;
    }

    public String exportSession(String sessionId, String format) {
        var entries = loadSession(sessionId);
        if (entries.isEmpty()) return null;

        if ("json".equalsIgnoreCase(format)) {
            try {
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
            } catch (IOException e) {
                log.error("Failed to export session as JSON: {}", sessionId, e);
                return null;
            }
        }

        // Markdown is the canonical text-export format; HTML is a thin wrapper
        // that escapes the markdown and surrounds it with a self-contained
        // page. Previously ?format=html silently returned markdown (F21) —
        // browser-side downloaders saved a .html file containing raw markdown.
        StringBuilder sb = new StringBuilder();
        sb.append("# Conversation: ").append(sessionId).append("\n\n");
        for (var entry : entries) {
            String type = (String) entry.get("type");
            if ("session_start".equals(type)) {
                sb.append("*Session started: ").append(entry.get("timestamp")).append("*\n\n");
                continue;
            }
            if (!"message".equals(type)) continue;

            String role = (String) entry.get("role");
            String content = (String) entry.get("content");
            String timestamp = (String) entry.get("timestamp");

            if ("user".equals(role)) {
                sb.append("**User** ");
            } else {
                sb.append("**Assistant** ");
            }
            if (timestamp != null) {
                sb.append("_").append(timestamp).append("_");
            }
            sb.append("\n\n").append(content).append("\n\n---\n\n");
        }
        String markdown = sb.toString();
        if ("html".equalsIgnoreCase(format)) {
            return wrapMarkdownAsHtml(sessionId, markdown);
        }
        return markdown;
    }

    private String wrapMarkdownAsHtml(String sessionId, String markdown) {
        String escaped =
                markdown.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<title>Conversation · " + sessionId + "</title>"
                + "<style>body{font-family:-apple-system,system-ui,sans-serif;"
                + "max-width:840px;margin:2em auto;padding:0 1em;"
                + "background:#0e0e0e;color:#e0e0e0;line-height:1.5}"
                + "pre{white-space:pre-wrap;word-break:break-word;"
                + "background:#1a1a1a;padding:1em;border-radius:6px;"
                + "border:1px solid #2a2a2a}</style></head><body>"
                + "<pre>" + escaped + "</pre></body></html>";
    }

    public boolean setTitle(String sessionId, String title) {
        if (!isValidSessionId(sessionId)) return false;
        Path file = baseDir.resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) return false;
        Path metaFile = baseDir.resolve(sessionId + ".meta.json");
        try {
            Map<String, String> meta = new LinkedHashMap<>();
            if (Files.exists(metaFile)) {
                @SuppressWarnings("unchecked")
                Map<String, String> existing = mapper.readValue(Files.readString(metaFile), Map.class);
                meta.putAll(existing);
            }
            meta.put("title", title);
            Files.writeString(metaFile, mapper.writeValueAsString(meta));
            return true;
        } catch (IOException e) {
            log.error("Failed to set title for session: {}", sessionId, e);
            return false;
        }
    }

    public String getTitle(String sessionId) {
        if (!isValidSessionId(sessionId)) return null;
        Path metaFile = baseDir.resolve(sessionId + ".meta.json");
        if (!Files.exists(metaFile)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> meta = mapper.readValue(Files.readString(metaFile), Map.class);
            return meta.get("title");
        } catch (IOException e) {
            log.debug("Failed to read session title for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    public boolean deleteSession(String sessionId) {
        if (!isValidSessionId(sessionId)) return false;
        Path file = baseDir.resolve(sessionId + ".jsonl");
        try {
            boolean deleted = Files.deleteIfExists(file);
            Files.deleteIfExists(baseDir.resolve(sessionId + ".meta.json"));
            return deleted;
        } catch (IOException e) {
            log.error("Failed to delete session: {}", sessionId, e);
            return false;
        }
    }

    public List<Map<String, Object>> loadMostRecentMessages() {
        try (var stream = Files.list(baseDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(
                                    Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .map(p -> {
                        String id = p.getFileName().toString().replace(".jsonl", "");
                        return loadSession(id).stream()
                                .filter(e -> "message".equals(e.get("type")))
                                .toList();
                    })
                    .orElse(List.of());
        } catch (IOException e) {
            log.error("Failed to find most recent session", e);
            return List.of();
        }
    }

    private String extractSnippet(String content, String lowerQuery) {
        int idx = content.toLowerCase().indexOf(lowerQuery);
        if (idx < 0) return truncate(content, 150);
        int start = Math.max(0, idx - 60);
        int end = Math.min(content.length(), idx + lowerQuery.length() + 60);
        StringBuilder sb = new StringBuilder();
        if (start > 0) sb.append("...");
        String segment = content.substring(start, end);
        int matchStart = idx - start;
        int matchEnd = matchStart + lowerQuery.length();
        sb.append(segment, 0, matchStart);
        sb.append(">>>").append(segment, matchStart, matchEnd).append("<<<");
        sb.append(segment, matchEnd, segment.length());
        if (end < content.length()) sb.append("...");
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private String getSessionPreview(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                if (line.contains("\"role\":\"user\"") || line.contains("\"role\": \"user\"")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = mapper.readValue(line, Map.class);
                    String content = (String) entry.get("content");
                    if (content != null) {
                        return content.length() > PREVIEW_MAX_LENGTH
                            ? content.substring(0, PREVIEW_MAX_LENGTH - 3) + "..." : content;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read session preview from {}: {}", file.getFileName(), e.getMessage());
        }
        return "(empty)";
    }

    private void appendEntry(Map<String, ?> entry) {
        if (currentFile == null) return;
        try (BufferedWriter w = Files.newBufferedWriter(currentFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(mapper.writeValueAsString(entry));
            w.newLine();
        } catch (IOException e) {
            log.error("Failed to append to conversation log", e);
        }
    }
}
