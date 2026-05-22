/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only JSONL conversation store keyed by session id, used by the CLI's {@code --print
 * --session-id <id>} mode to persist multi-turn state between invocations.
 *
 * <p>One file per session: {@code <dataDir>/sessions/<sessionId>.jsonl}. Each line is one
 * serialized {@link Msg}. The store is intentionally minimal — no migration framework, no
 * compaction, no concurrent-write guarantees — because the CLI use case is single-process,
 * single-writer per session.
 *
 * <p>For richer use cases (web Console, channels) the gateway / Console has its own
 * conversation persistence layer; this one exists specifically to make {@code kairo-assistant
 * --print --session-id} a working dev/eval flow.
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;

    public SessionStore(Path dataDir) {
        this.root = dataDir.resolve("sessions");
    }

    /** Read the full ordered conversation for {@code sessionId}, empty if no file yet. */
    public List<Msg> load(String sessionId) {
        Path file = pathFor(sessionId);
        if (!Files.exists(file)) return List.of();
        List<Msg> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                Msg msg = decode(line);
                if (msg != null) out.add(msg);
            }
        } catch (IOException e) {
            log.warn("Failed to read session {} from {}: {}", sessionId, file, e.getMessage());
        }
        return out;
    }

    /** Append messages to the session log. Creates parent dirs + file as needed. */
    public void append(String sessionId, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return;
        Path file = pathFor(sessionId);
        try {
            Files.createDirectories(file.getParent());
            StringBuilder buf = new StringBuilder();
            for (Msg msg : messages) {
                buf.append(encode(msg)).append('\n');
            }
            Files.writeString(
                    file,
                    buf.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to append to session {} at {}: {}", sessionId, file, e.getMessage());
        }
    }

    /** Path resolution helper (visible for tests). */
    public Path pathFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        String safe = sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safe + ".jsonl");
    }

    /** Visible for tests — the root sessions directory. */
    public Path root() {
        return root;
    }

    // ---- JSON encode/decode ---------------------------------------------------------------

    private static String encode(Msg msg) throws JsonProcessingException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", msg.role().name());
        ArrayNode contents = node.putArray("contents");
        for (Content c : msg.contents()) {
            ObjectNode cn = MAPPER.createObjectNode();
            if (c instanceof Content.TextContent t) {
                cn.put("type", "text");
                cn.put("text", t.text());
            } else if (c instanceof Content.ToolUseContent tu) {
                cn.put("type", "tool_use");
                cn.put("id", tu.toolId());
                cn.put("name", tu.toolName());
                cn.set("input", MAPPER.valueToTree(tu.input() == null ? Map.of() : tu.input()));
            } else if (c instanceof Content.ToolResultContent tr) {
                cn.put("type", "tool_result");
                cn.put("toolUseId", tr.toolUseId());
                cn.put("content", tr.content() == null ? "" : tr.content());
                cn.put("isError", tr.isError());
            } else {
                // Unknown content type — store best-effort string so the file stays valid JSONL.
                cn.put("type", "unknown");
                cn.put("text", String.valueOf(c));
            }
            contents.add(cn);
        }
        return MAPPER.writeValueAsString(node);
    }

    @SuppressWarnings("unchecked")
    private static Msg decode(String line) {
        try {
            ObjectNode node = (ObjectNode) MAPPER.readTree(line);
            MsgRole role = MsgRole.valueOf(node.path("role").asText("USER"));
            Msg.Builder builder = Msg.builder().role(role);
            ArrayNode contents = (ArrayNode) node.path("contents");
            if (contents == null || contents.isMissingNode()) {
                return builder.build();
            }
            for (var element : contents) {
                String type = element.path("type").asText();
                switch (type) {
                    case "text" -> builder.addContent(
                            new Content.TextContent(element.path("text").asText("")));
                    case "tool_use" -> {
                        Map<String, Object> input = element.has("input")
                                ? MAPPER.convertValue(element.get("input"), Map.class)
                                : new HashMap<>();
                        builder.addContent(
                                new Content.ToolUseContent(
                                        element.path("id").asText(),
                                        element.path("name").asText(),
                                        input));
                    }
                    case "tool_result" -> builder.addContent(
                            new Content.ToolResultContent(
                                    element.path("toolUseId").asText(),
                                    element.path("content").asText(""),
                                    element.path("isError").asBoolean(false)));
                    default -> builder.addContent(
                            new Content.TextContent(element.path("text").asText("")));
                }
            }
            return builder.build();
        } catch (Exception e) {
            log.debug("Skipping unparseable session line: {}", e.getMessage());
            return null;
        }
    }
}
