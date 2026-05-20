package io.kairo.assistant.server;

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
import io.kairo.api.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.cron.CronScheduler;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ChatControllerTest {

    private ChatController controller;
    private AtomicBoolean interruptCalled;

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
                TestFixtures.stubPluginManager(),
                config);

        var sessionManager = new SessionManager(session);
        controller = new ChatController(session, TestFixtures.stubGateway(agent), new SessionAwareDeltaRouter(), sessionManager, new StreamingDeltaRouter());
    }

    @Test
    void chatReturnsResponse() {
        var request = new ChatController.ChatRequest("hello");
        var result = controller.chat(request, null).block();

        assertNotNull(result);
        assertEquals("echo: hello", result.get("response"));
        assertEquals("ASSISTANT", result.get("role"));
        assertNotNull(result.get("sessionId"));
    }

    @Test
    void chatWithSessionIdReusesSession() {
        var request = new ChatController.ChatRequest("hello");
        var first = controller.chat(request, "session-1").block();
        var second = controller.chat(request, "session-1").block();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals("session-1", first.get("sessionId"));
        assertEquals("session-1", second.get("sessionId"));
    }

    @Test
    void chatRejectsBlankMessage() {
        var request = new ChatController.ChatRequest("  ");
        var result = controller.chat(request, null).block();

        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertEquals("message is required", result.get("error"));
    }

    @Test
    void chatRejectsNullMessage() {
        var request = new ChatController.ChatRequest(null);
        var result = controller.chat(request, null).block();

        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    @Test
    void interruptReturnsStatus() {
        controller.chat(new ChatController.ChatRequest("init"), "test-session").block();
        var result = controller.interrupt("test-session");
        assertEquals("interrupted", result.get("status"));
    }

    @Test
    void chatStreamRejectsBlankMessage() {
        var request = new ChatController.ChatRequest("");
        var events = controller.chatStream(request, null).collectList().block();

        assertNotNull(events);
        assertFalse(events.isEmpty());
        assertTrue(events.get(0).contains("error"));
        assertTrue(events.get(0).contains("message is required"));
    }

    @Test
    void chatStreamProducesEvents() {
        var request = new ChatController.ChatRequest("hello");
        var events = controller.chatStream(request, null).collectList().block();

        assertNotNull(events);
        assertFalse(events.isEmpty());
        boolean hasDone = events.stream().anyMatch(e -> e.contains("\"type\":\"done\""));
        assertTrue(hasDone, "Stream should end with a 'done' event");
    }

    @Test
    void chatRequestRecordFields() {
        var request = new ChatController.ChatRequest("test msg");
        assertEquals("test msg", request.message());
    }

    @Test
    void chatHandlesAgentError() {
        Agent failingAgent = new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.error(new RuntimeException("agent failed"));
            }
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };

        var config = AssistantConfig.builder().apiKey("test").build();
        var toolRegistry = new DefaultToolRegistry();
        var session = new AssistantSession(
                failingAgent, toolRegistry, new StubToolExecutor(),
                new InMemoryStore(), new StubCronScheduler(),
                AssistantSkills.createRegistry(),
                TestFixtures.stubPluginManager(),
                config);
        var errorController = new ChatController(session, TestFixtures.stubGateway(failingAgent), new SessionAwareDeltaRouter(), new SessionManager(session), new StreamingDeltaRouter());

        var result = errorController.chat(new ChatController.ChatRequest("hello"), null).block();
        assertNotNull(result);
        assertEquals("agent failed", result.get("error"));
    }

    @Test
    void chatStreamHandlesAgentError() {
        Agent failingAgent = new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.error(new RuntimeException("stream failed"));
            }
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };

        var config = AssistantConfig.builder().apiKey("test").build();
        var toolRegistry = new DefaultToolRegistry();
        var session = new AssistantSession(
                failingAgent, toolRegistry, new StubToolExecutor(),
                new InMemoryStore(), new StubCronScheduler(),
                AssistantSkills.createRegistry(),
                TestFixtures.stubPluginManager(),
                config);
        var errorController = new ChatController(session, TestFixtures.stubGateway(failingAgent), new SessionAwareDeltaRouter(), new SessionManager(session), new StreamingDeltaRouter());

        var events = errorController.chatStream(new ChatController.ChatRequest("hello"), null)
                .collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.contains("error")));
    }

    @Test
    void chatGeneratesSessionIdWhenNoneProvided() {
        var result = controller.chat(new ChatController.ChatRequest("hello"), null).block();
        assertNotNull(result);
        String sessionId = (String) result.get("sessionId");
        assertNotNull(sessionId);
        assertFalse(sessionId.isBlank());
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
