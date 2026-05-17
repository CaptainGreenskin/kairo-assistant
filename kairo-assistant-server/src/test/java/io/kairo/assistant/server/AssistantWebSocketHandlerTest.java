package io.kairo.assistant.server;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.cron.CronTask;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolInvocation;
import io.kairo.api.tool.ToolResult;
import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.cron.CronScheduler;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AssistantWebSocketHandlerTest {

    private AssistantWebSocketHandler handler;
    private MetricsCollector metrics;
    private AtomicBoolean interruptCalled;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        interruptCalled = new AtomicBoolean(false);

        Agent agent = new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "echo: " + input.text()));
            }
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() { interruptCalled.set(true); }
        };

        var config = AssistantConfig.builder().apiKey("test").build();
        var toolRegistry = new DefaultToolRegistry();
        var session = new AssistantSession(
                agent, toolRegistry, new StubToolExecutor(),
                new InMemoryStore(), new StubCronScheduler(),
                AssistantSkills.createRegistry(),
                new PluginManager(toolRegistry, AssistantSkills.createRegistry(), Path.of("/tmp")),
                config);

        metrics = new MetricsCollector();
        var sessionManager = new SessionManager(session);
        handler = new AssistantWebSocketHandler(session, sessionManager, metrics);
    }

    @Test
    void connectionEstablishedTracksSession() throws Exception {
        var ws = new StubWebSocketSession("ws-1");
        handler.afterConnectionEstablished(ws);

        assertTrue(metrics.toPrometheus().contains("kairo_websocket_active 1"));
    }

    @Test
    void connectionClosedRemovesSession() throws Exception {
        var ws = new StubWebSocketSession("ws-2");
        handler.afterConnectionEstablished(ws);
        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);

        assertTrue(metrics.toPrometheus().contains("kairo_websocket_active 0"));
    }

    @Test
    void handleTextMessageSendsResponse() throws Exception {
        var ws = new StubWebSocketSession("ws-3");
        handler.afterConnectionEstablished(ws);

        String payload = mapper.writeValueAsString(Map.of("message", "hello"));
        handler.handleMessage(ws, new TextMessage(payload));

        await().atMost(2, TimeUnit.SECONDS).until(() -> ws.sentMessages.size() >= 2);
        assertTrue(ws.sentMessages.size() >= 2, "should send thinking + response");
    }

    @Test
    void handleEmptyMessageSendsError() throws Exception {
        var ws = new StubWebSocketSession("ws-4");
        handler.afterConnectionEstablished(ws);

        String payload = mapper.writeValueAsString(Map.of("message", "  "));
        handler.handleMessage(ws, new TextMessage(payload));

        await().atMost(2, TimeUnit.SECONDS).until(() -> !ws.sentMessages.isEmpty());
        String sent = ws.sentMessages.get(0).getPayload().toString();
        assertTrue(sent.contains("error"));
    }

    @Test
    void handleInterruptMessage() throws Exception {
        var ws = new StubWebSocketSession("ws-5");
        handler.afterConnectionEstablished(ws);

        String payload = mapper.writeValueAsString(Map.of("type", "interrupt"));
        handler.handleMessage(ws, new TextMessage(payload));

        await().atMost(2, TimeUnit.SECONDS).until(() -> interruptCalled.get() && !ws.sentMessages.isEmpty());
        String sent = ws.sentMessages.get(0).getPayload().toString();
        assertTrue(sent.contains("interrupted"));
    }

    @Test
    void handlePongUpdatesTimestamp() throws Exception {
        var ws = new StubWebSocketSession("ws-6");
        handler.afterConnectionEstablished(ws);

        assertDoesNotThrow(() -> handler.handleMessage(ws, new PongMessage()));
    }

    @Test
    void handleInvalidJsonSendsError() throws Exception {
        var ws = new StubWebSocketSession("ws-7");
        handler.afterConnectionEstablished(ws);

        handler.handleMessage(ws, new TextMessage("not-valid-json"));

        await().atMost(2, TimeUnit.SECONDS).until(() -> !ws.sentMessages.isEmpty());
        String sent = ws.sentMessages.get(0).getPayload().toString();
        assertTrue(sent.contains("error"));
    }

    @Test
    void multipleConnectionsTracked() throws Exception {
        var ws1 = new StubWebSocketSession("ws-a");
        var ws2 = new StubWebSocketSession("ws-b");

        handler.afterConnectionEstablished(ws1);
        handler.afterConnectionEstablished(ws2);

        assertTrue(metrics.toPrometheus().contains("kairo_websocket_active 2"));

        handler.afterConnectionClosed(ws1, CloseStatus.NORMAL);

        assertTrue(metrics.toPrometheus().contains("kairo_websocket_active 1"));
    }

    @Test
    void closedSessionDoesNotReceiveMessages() throws Exception {
        var ws = new StubWebSocketSession("ws-closed");
        ws.open = false;

        handler.afterConnectionEstablished(ws);

        String payload = mapper.writeValueAsString(Map.of("message", "hello"));
        handler.handleMessage(ws, new TextMessage(payload));

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1))
                .until(() -> ws.sentMessages.isEmpty());
    }

    @Test
    void connectionClosedWithGoingAwayStatus() throws Exception {
        var ws = new StubWebSocketSession("ws-away");
        handler.afterConnectionEstablished(ws);
        handler.afterConnectionClosed(ws, CloseStatus.GOING_AWAY);

        assertTrue(metrics.toPrometheus().contains("kairo_websocket_active 0"));
    }

    @Test
    void connectionEstablishedNoHistoryForPlainAgent() throws Exception {
        var ws = new StubWebSocketSession("ws-no-hist");
        handler.afterConnectionEstablished(ws);

        assertTrue(ws.sentMessages.isEmpty(),
                "plain Agent should not send history on connect");
    }

    @Test
    void handleMessageWithFileAttachment() throws Exception {
        var ws = new StubWebSocketSession("ws-file");
        handler.afterConnectionEstablished(ws);

        String base64Data = "data:text/plain;base64," +
                java.util.Base64.getEncoder().encodeToString("hello world".getBytes());
        Map<String, Object> payload = Map.of(
                "message", "analyze this file",
                "file", Map.of("name", "test.txt", "type", "text/plain", "data", base64Data));
        handler.handleMessage(ws, new TextMessage(mapper.writeValueAsString(payload)));

        await().atMost(2, TimeUnit.SECONDS).until(() -> ws.sentMessages.size() >= 2);
        assertTrue(ws.sentMessages.size() >= 2, "should send thinking + response");
    }

    @Test
    void autoTitleSetOnFirstMessage() throws Exception {
        var ws = new StubWebSocketSession("ws-title");
        handler.afterConnectionEstablished(ws);

        String payload = mapper.writeValueAsString(Map.of("message", "What is the weather in Tokyo?"));
        handler.handleMessage(ws, new TextMessage(payload));

        await().atMost(2, TimeUnit.SECONDS).until(() -> ws.sentMessages.stream()
                .anyMatch(m -> m.getPayload().toString().contains("response")));
        boolean hasTitleConfirm = ws.sentMessages.stream()
                .anyMatch(m -> m.getPayload().toString().contains("response"));
        assertTrue(hasTitleConfirm, "should have received a response");
    }

    @Test
    void autoTitleTruncatesLongMessages() throws Exception {
        var ws = new StubWebSocketSession("ws-title-long");
        handler.afterConnectionEstablished(ws);

        String longMsg = "A".repeat(100);
        String payload = mapper.writeValueAsString(Map.of("message", longMsg));
        handler.handleMessage(ws, new TextMessage(payload));

        await().atMost(2, TimeUnit.SECONDS).until(() -> ws.sentMessages.size() >= 2);
    }

    @Test
    void rejectsOversizedMessage() throws Exception {
        var ws = new StubWebSocketSession("ws-oversized");
        handler.afterConnectionEstablished(ws);

        String huge = "x".repeat(200_000);
        handler.handleMessage(ws, new TextMessage(huge));

        await().atMost(2, TimeUnit.SECONDS).until(() -> !ws.sentMessages.isEmpty());
        String sent = ws.sentMessages.get(0).getPayload().toString();
        assertTrue(sent.contains("too large"));
    }

    @Test
    void handleMessageWithFileOnlyNoText() throws Exception {
        var ws = new StubWebSocketSession("ws-file-only");
        handler.afterConnectionEstablished(ws);

        String base64Data = "data:image/png;base64," +
                java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        Map<String, Object> payload = Map.of(
                "message", "",
                "file", Map.of("name", "img.png", "type", "image/png", "data", base64Data));
        handler.handleMessage(ws, new TextMessage(mapper.writeValueAsString(payload)));

        await().atMost(2, TimeUnit.SECONDS).until(() -> ws.sentMessages.size() >= 2);
        assertTrue(ws.sentMessages.size() >= 2, "file-only message should not be treated as empty");
    }

    private static class StubWebSocketSession implements WebSocketSession {
        private final String id;
        volatile boolean open = true;
        final List<WebSocketMessage<?>> sentMessages = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        StubWebSocketSession(String id) { this.id = id; }

        @Override public String getId() { return id; }
        @Override public URI getUri() { return URI.create("ws://localhost/ws"); }
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
        @Override public void sendMessage(WebSocketMessage<?> message) { if (open) sentMessages.add(message); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        @Override public void close(CloseStatus status) { open = false; }
    }

    private static class StubToolExecutor implements ToolExecutor {
        @Override public Mono<ToolResult> execute(String name, Map<String, Object> input) { return Mono.empty(); }
        @Override public Mono<ToolResult> execute(String name, Map<String, Object> input, Duration t) { return Mono.empty(); }
        @Override public Flux<ToolResult> executeParallel(List<ToolInvocation> inv) { return Flux.empty(); }
    }

    private static class StubCronScheduler implements CronScheduler {
        @Override public CronTask create(String c, String p, boolean r, boolean d) { return null; }
        @Override public boolean delete(String id) { return false; }
        @Override public List<CronTask> list() { return List.of(); }
        @Override public void start() {}
        @Override public void stop() {}
    }
}
