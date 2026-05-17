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

    public String startSession() {
        currentSessionId = UUID.randomUUID().toString().substring(0, 8);
        currentFile = baseDir.resolve(currentSessionId + ".jsonl");
        appendEntry(Map.of(
                "type", "session_start",
                "sessionId", currentSessionId,
                "timestamp", Instant.now().toString()));
        return currentSessionId;
    }

    public void endSession() {
        if (currentFile == null) return;
        appendEntry(Map.of(
                "type", "session_end",
                "sessionId", currentSessionId != null ? currentSessionId : "",
                "timestamp", Instant.now().toString()));
    }

    public void appendMessage(String role, String content) {
        if (currentFile == null) startSession();
        appendEntry(Map.of(
                "type", "message",
                "role", role,
                "content", content,
                "timestamp", Instant.now().toString()));
    }

    public List<Map<String, Object>> loadSession(String sessionId) {
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
                            return 0;
                        }
                    })
                    .forEach(p -> {
                        String id = p.getFileName().toString().replace(".jsonl", "");
                        String preview = getSessionPreview(p);
                        Map<String, String> info = new LinkedHashMap<>();
                        info.put("id", id);
                        info.put("preview", preview);
                        String title = getTitle(id);
                        if (title != null) {
                            info.put("title", title);
                        }
                        try {
                            info.put("lastModified",
                                    Files.getLastModifiedTime(p).toInstant().toString());
                        } catch (IOException ignored) {
                        }
                        sessions.add(info);
                    });
        } catch (IOException e) {
            log.error("Failed to list sessions", e);
        }
        return sessions;
    }

    public String currentSessionId() {
        return currentSessionId;
    }

    public List<Map<String, Object>> search(String query) {
        List<Map<String, Object>> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        try (var stream = Files.list(baseDir)) {
            stream.filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        String sessionId = file.getFileName().toString().replace(".jsonl", "");
                        try {
                            for (String line : Files.readAllLines(file)) {
                                if (line.isBlank()) continue;
                                if (line.toLowerCase().contains(lowerQuery)) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> entry = mapper.readValue(line, Map.class);
                                    if ("message".equals(entry.get("type"))) {
                                        Map<String, Object> result = new LinkedHashMap<>(entry);
                                        result.put("sessionId", sessionId);
                                        results.add(result);
                                    }
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to search conversations", e);
        }
        return results;
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
        return sb.toString();
    }

    public boolean setTitle(String sessionId, String title) {
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
        Path metaFile = baseDir.resolve(sessionId + ".meta.json");
        if (!Files.exists(metaFile)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> meta = mapper.readValue(Files.readString(metaFile), Map.class);
            return meta.get("title");
        } catch (IOException e) {
            return null;
        }
    }

    public boolean deleteSession(String sessionId) {
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

    private String getSessionPreview(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                if (line.contains("\"role\":\"user\"") || line.contains("\"role\": \"user\"")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = mapper.readValue(line, Map.class);
                    String content = (String) entry.get("content");
                    if (content != null) {
                        return content.length() > 60 ? content.substring(0, 57) + "..." : content;
                    }
                }
            }
        } catch (IOException ignored) {
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
