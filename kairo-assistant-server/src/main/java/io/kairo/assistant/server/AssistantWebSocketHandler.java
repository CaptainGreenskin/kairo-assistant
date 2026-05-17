package io.kairo.assistant.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.tool.ToolCallLogger;
import io.kairo.core.agent.DefaultReActAgent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class AssistantWebSocketHandler extends TextWebSocketHandler implements EventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(AssistantWebSocketHandler.class);
    private static final long PING_INTERVAL_SECONDS = 30;
    private static final int MAX_MESSAGE_SIZE = 102_400;

    private final AssistantSession session;
    private final SessionManager sessionManager;
    private final MetricsCollector metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastPongTimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-ping");
        t.setDaemon(true);
        return t;
    });

    public AssistantWebSocketHandler(AssistantSession session, SessionManager sessionManager, MetricsCollector metrics) {
        this.session = session;
        this.sessionManager = sessionManager;
        this.metrics = metrics;
        wireStreaming();
        wireToolEvents();
        startPingSchedule();
    }

    @PreDestroy
    public void shutdown() {
        pingScheduler.shutdownNow();
        log.info("WebSocket ping scheduler shut down");
    }

    private final java.util.function.Consumer<String> wsBroadcastConsumer = delta -> {
        for (WebSocketSession ws : activeSessions.values()) {
            if (ws.isOpen()) {
                sendJson(ws, Map.of("type", "delta", "content", delta));
            }
        }
    };

    private void wireStreaming() {
        if (session.agent() instanceof DefaultReActAgent agent) {
            agent.setTextDeltaConsumer(wsBroadcastConsumer);
        }
    }

    public java.util.function.Consumer<String> broadcastConsumer() {
        return wsBroadcastConsumer;
    }

    private void wireToolEvents() {
        if (session.toolExecutor() instanceof ToolCallLogger logger) {
            logger.addListener(record -> {
                Map<String, Object> event = Map.of(
                        "type", "tool_use",
                        "tool", record.toolName(),
                        "durationMs", record.durationMs(),
                        "success", record.success(),
                        "timestamp", record.timestamp().toString());
                for (WebSocketSession ws : activeSessions.values()) {
                    if (ws.isOpen()) {
                        sendJson(ws, event);
                    }
                }
            });
        }
    }

    private void startPingSchedule() {
        pingScheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (var entry : activeSessions.entrySet()) {
                WebSocketSession ws = entry.getValue();
                try {
                    if (ws.isOpen()) {
                        Long lastPong = lastPongTimes.get(entry.getKey());
                        if (lastPong != null && now - lastPong > PING_INTERVAL_SECONDS * 3 * 1000) {
                            log.warn("WebSocket {} missed pong, closing", entry.getKey());
                            ws.close(CloseStatus.SESSION_NOT_RELIABLE);
                            continue;
                        }
                        synchronized (ws) {
                            ws.sendMessage(new PingMessage());
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to ping {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        activeSessions.put(wsSession.getId(), wsSession);
        lastPongTimes.put(wsSession.getId(), System.currentTimeMillis());
        sessionManager.getOrCreate(wsSession.getId());
        metrics.wsConnected();
        log.info("WebSocket connected: {}", wsSession.getId());
        sendConversationHistory(wsSession);
    }

    private void sendConversationHistory(WebSocketSession wsSession) {
        if (!(session.agent() instanceof DefaultReActAgent agent)) return;
        List<Msg> history = agent.conversationHistory();
        if (history == null || history.isEmpty()) return;

        List<Map<String, String>> messages = new ArrayList<>();
        for (Msg msg : history) {
            String role = msg.role().name().toLowerCase();
            if ("user".equals(role) || "assistant".equals(role)) {
                messages.add(Map.of("role", role, "content", msg.text()));
            }
        }
        if (!messages.isEmpty()) {
            sendJson(wsSession, Map.of("type", "history", "messages", messages));
            log.debug("Sent {} history messages to {}", messages.size(), wsSession.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
        if (message.getPayloadLength() > MAX_MESSAGE_SIZE) {
            sendJson(wsSession, Map.of("type", "error",
                    "message", "Message too large (max " + MAX_MESSAGE_SIZE / 1024 + "KB)"));
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = mapper.readValue(message.getPayload(), Map.class);
            String text = String.valueOf(payload.getOrDefault("message", ""));
            String type = String.valueOf(payload.getOrDefault("type", "message"));

            if ("interrupt".equals(type)) {
                session.agent().interrupt();
                sendJson(wsSession, Map.of("type", "interrupted"));
                return;
            }

            String fileRef = handleFileAttachment(payload);
            if (fileRef != null) {
                text = text.isBlank() ? fileRef : text + "\n\n" + fileRef;
            }

            if (text.isBlank()) {
                sendJson(wsSession, Map.of("type", "error", "message", "empty message"));
                return;
            }

            sendJson(wsSession, Map.of("type", "thinking"));
            broadcast(Map.of("type", "agent_state", "state", "THINKING"));

            var clientSession = sessionManager.getOrCreate(wsSession.getId());
            clientSession.conversationStore().appendMessage("user", text);
            int msgNum = clientSession.incrementMessages();
            metrics.recordMessage();

            if (msgNum == 1) {
                String title = text.length() > 50 ? text.substring(0, 47) + "..." : text;
                title = title.replaceAll("[\\r\\n]+", " ").trim();
                clientSession.conversationStore().setTitle(
                        clientSession.conversationStore().currentSessionId(), title);
            }

            long tokensBefore = session.agent() instanceof DefaultReActAgent ra
                    ? ra.totalTokensUsed() : 0;
            Msg input = Msg.of(MsgRole.USER, text);
            long callStart = System.currentTimeMillis();
            session.agent().call(input)
                    .subscribe(
                            response -> {
                                broadcast(Map.of("type", "agent_state", "state", "IDLE"));
                                metrics.recordAgentCall(System.currentTimeMillis() - callStart);
                                if (session.agent() instanceof DefaultReActAgent ra2) {
                                    long delta = ra2.totalTokensUsed() - tokensBefore;
                                    if (delta > 0) metrics.recordTokenUsage(0, delta);
                                }
                                clientSession.conversationStore().appendMessage("assistant", response.text());
                                var responseData = new HashMap<String, Object>();
                                responseData.put("type", "response");
                                responseData.put("content", response.text());
                                responseData.put("role", response.role().name());
                                responseData.put("durationMs", System.currentTimeMillis() - callStart);
                                if (session.agent() instanceof DefaultReActAgent ra3) {
                                    responseData.put("totalTokens", ra3.totalTokensUsed());
                                }
                                sendJson(wsSession, responseData);
                            },
                            error -> {
                                broadcast(Map.of("type", "agent_state", "state", "IDLE"));
                                metrics.recordAgentError();
                                sendJson(wsSession, Map.of(
                                        "type", "error",
                                        "message", error.getMessage()));
                            });
        } catch (Exception e) {
            log.error("Error handling WebSocket message", e);
            sendJson(wsSession, Map.of("type", "error", "message", e.getMessage()));
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession wsSession, PongMessage message) {
        lastPongTimes.put(wsSession.getId(), System.currentTimeMillis());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        activeSessions.remove(wsSession.getId());
        lastPongTimes.remove(wsSession.getId());
        var clientSession = sessionManager.get(wsSession.getId());
        if (clientSession != null && clientSession.messageCount() > 0) {
            try {
                clientSession.conversationStore().endSession();
                log.info("Auto-saved conversation for session {} ({} messages)",
                        wsSession.getId(), clientSession.messageCount());
            } catch (Exception e) {
                log.warn("Failed to auto-save conversation: {}", e.getMessage());
            }
        }
        sessionManager.remove(wsSession.getId());
        metrics.wsDisconnected();
        log.info("WebSocket disconnected: {} ({})", wsSession.getId(), status);
    }

    @Override
    public void broadcast(Map<String, Object> event) {
        for (WebSocketSession ws : activeSessions.values()) {
            if (ws.isOpen()) {
                sendJson(ws, event);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String handleFileAttachment(Map<String, Object> payload) {
        Object fileObj = payload.get("file");
        if (!(fileObj instanceof Map)) return null;
        Map<String, Object> file = (Map<String, Object>) fileObj;
        String name = String.valueOf(file.getOrDefault("name", "upload"));
        String data = String.valueOf(file.getOrDefault("data", ""));
        if (data.isEmpty()) return null;

        try {
            Path uploadDir = Path.of(session.config().dataDir(), "uploads");
            Files.createDirectories(uploadDir);
            String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            String uniqueName = System.currentTimeMillis() + "_" + safeName;
            Path target = uploadDir.resolve(uniqueName);

            int commaIdx = data.indexOf(',');
            byte[] bytes = Base64.getDecoder().decode(commaIdx >= 0 ? data.substring(commaIdx + 1) : data);
            Files.write(target, bytes);
            log.info("Saved uploaded file: {} ({} bytes)", target, bytes.length);
            return "[Attached file: " + name + " saved to " + target.toAbsolutePath() + "]";
        } catch (IOException e) {
            log.warn("Failed to save uploaded file: {}", e.getMessage());
            return "[File upload failed: " + name + "]";
        }
    }

    private void sendJson(WebSocketSession wsSession, Map<String, Object> data) {
        try {
            if (wsSession.isOpen()) {
                synchronized (wsSession) {
                    wsSession.sendMessage(new TextMessage(mapper.writeValueAsString(data)));
                }
            }
        } catch (Exception e) {
            log.error("Failed to send WebSocket message", e);
        }
    }
}
