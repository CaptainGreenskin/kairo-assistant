package io.kairo.assistant.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.assistant.gateway.SessionKey;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MirrorWebSocketHandlerTest {

    private SessionMirror mirror;
    private MirrorWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        mirror = new SessionMirror();
        handler = new MirrorWebSocketHandler(mirror);
    }

    @Test
    void subscribeAllRegistersGlobalListener() throws Exception {
        var ws = new StubSession("s1");
        handler.afterConnectionEstablished(ws);
        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"subscribe_all\"}"));

        assertThat(mirror.subscriberCount()).isEqualTo(1);
    }

    @Test
    void globalSubscriberReceivesMessages() throws Exception {
        var ws = new StubSession("s2");
        handler.afterConnectionEstablished(ws);
        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"subscribe_all\"}"));

        mirror.onInbound(SessionKey.of("test", "user1"), "test", "hello");

        assertThat(ws.sent).hasSize(2); // ack + message
        assertThat(ws.sent.get(1).getPayload().toString()).contains("hello");
    }

    @Test
    void subscribeSessionRegistersListener() throws Exception {
        var ws = new StubSession("s3");
        handler.afterConnectionEstablished(ws);
        handler.handleTextMessage(ws,
                new TextMessage("{\"action\":\"subscribe_session\",\"platform\":\"dingtalk\",\"userId\":\"u1\"}"));

        assertThat(mirror.subscriberCount()).isEqualTo(1);
    }

    @Test
    void subscribeUserRegistersListener() throws Exception {
        var ws = new StubSession("s4");
        handler.afterConnectionEstablished(ws);
        handler.handleTextMessage(ws,
                new TextMessage("{\"action\":\"subscribe_user\",\"userId\":\"user1\"}"));

        assertThat(mirror.subscriberCount()).isEqualTo(1);
    }

    @Test
    void disconnectCleansUpGlobalSubscription() throws Exception {
        var ws = new StubSession("s5");
        handler.afterConnectionEstablished(ws);
        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"subscribe_all\"}"));
        assertThat(mirror.subscriberCount()).isEqualTo(1);

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
        assertThat(mirror.subscriberCount()).isEqualTo(0);
    }

    @Test
    void disconnectCleansUpSessionSubscription() throws Exception {
        var ws = new StubSession("s6");
        handler.afterConnectionEstablished(ws);
        handler.handleTextMessage(ws,
                new TextMessage("{\"action\":\"subscribe_session\",\"platform\":\"slack\",\"userId\":\"u2\"}"));
        assertThat(mirror.subscriberCount()).isEqualTo(1);

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
        assertThat(mirror.subscriberCount()).isEqualTo(0);
    }

    @Test
    void statsActionReturnsResponse() throws Exception {
        var ws = new StubSession("s7");
        handler.afterConnectionEstablished(ws);
        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"stats\"}"));

        assertThat(ws.sent).isNotEmpty();
        assertThat(ws.sent.get(0).getPayload().toString()).contains("totalListeners");
    }

    @Test
    void unknownActionSendsError() throws Exception {
        var ws = new StubSession("s8");
        handler.afterConnectionEstablished(ws);
        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"bad\"}"));

        assertThat(ws.sent).isNotEmpty();
        assertThat(ws.sent.get(0).getPayload().toString()).contains("error");
    }

    private static class StubSession implements WebSocketSession {
        private final String id;
        volatile boolean open = true;
        final List<WebSocketMessage<?>> sent = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        StubSession(String id) { this.id = id; }

        @Override public String getId() { return id; }
        @Override public URI getUri() { return URI.create("ws://localhost/api/mirror"); }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int limit) {}
        @Override public int getTextMessageSizeLimit() { return 65536; }
        @Override public void setBinaryMessageSizeLimit(int limit) {}
        @Override public int getBinaryMessageSizeLimit() { return 65536; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public void sendMessage(WebSocketMessage<?> message) { if (open) sent.add(message); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        @Override public void close(CloseStatus status) { open = false; }
    }
}
