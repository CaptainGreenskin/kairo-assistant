package io.kairo.assistant.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.assistant.gateway.SessionKey;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class MirrorWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MirrorWebSocketHandler.class);

    private final SessionMirror mirror;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, SubscriptionState> connections = new ConcurrentHashMap<>();

    public MirrorWebSocketHandler(SessionMirror mirror) {
        this.mirror = mirror;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Mirror client connected: {}", session.getId());
        connections.put(session.getId(), new SubscriptionState(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode json = mapper.readTree(message.getPayload());
            String action = json.path("action").asText();

            switch (action) {
                case "subscribe_session" -> {
                    String platform = json.path("platform").asText();
                    String userId = json.path("userId").asText();
                    SessionKey key = SessionKey.of(platform, userId);
                    SubscriptionState state = connections.get(session.getId());
                    if (state != null) {
                        Consumer<String> listener = msg -> safeSend(session, msg);
                        mirror.subscribe(key, listener);
                        state.sessionKey = key;
                        state.listener = listener;
                        sendAck(session, "subscribed to session " + platform + ":" + userId);
                    }
                }
                case "subscribe_user" -> {
                    String userId = json.path("userId").asText();
                    SubscriptionState state = connections.get(session.getId());
                    if (state != null) {
                        Consumer<String> listener = msg -> safeSend(session, msg);
                        mirror.subscribeUser(userId, listener);
                        state.userId = userId;
                        state.listener = listener;
                        sendAck(session, "subscribed to user " + userId);
                    }
                }
                case "stats" -> {
                    String stats = mapper.writeValueAsString(mirror.stats());
                    safeSend(session, stats);
                }
                default -> sendError(session, "Unknown action: " + action);
            }
        } catch (Exception e) {
            log.error("Failed to handle mirror message: {}", e.getMessage());
            sendError(session, "Invalid message: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SubscriptionState state = connections.remove(session.getId());
        if (state != null && state.listener != null) {
            if (state.sessionKey != null) {
                mirror.unsubscribe(state.sessionKey, state.listener);
            }
            if (state.userId != null) {
                mirror.unsubscribeUser(state.userId, state.listener);
            }
        }
        log.info("Mirror client disconnected: {}", session.getId());
    }

    private void safeSend(WebSocketSession session, String message) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(message));
            } catch (IOException e) {
                log.debug("Failed to send to mirror client: {}", e.getMessage());
            }
        }
    }

    private void sendAck(WebSocketSession session, String message) {
        safeSend(session, "{\"type\":\"ack\",\"message\":\"" + message + "\"}");
    }

    private void sendError(WebSocketSession session, String error) {
        safeSend(session, "{\"type\":\"error\",\"message\":\"" + error.replace("\"", "'") + "\"}");
    }

    private static class SubscriptionState {
        final WebSocketSession session;
        SessionKey sessionKey;
        String userId;
        Consumer<String> listener;

        SubscriptionState(WebSocketSession session) {
            this.session = session;
        }
    }
}
