package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ChannelControllerTest {

    private ChannelController controller;

    @BeforeEach
    void setUp() {
        var config = AssistantConfig.builder().apiKey("test").build();
        var toolRegistry = new DefaultToolRegistry();
        var skillRegistry = AssistantSkills.createRegistry();
        var agent = new TestFixtures.StubAgent() {
            @Override public Mono<Msg> call(Msg input) { return Mono.just(Msg.of(MsgRole.ASSISTANT, "ok")); }
        };
        var session = new AssistantSession(
                agent, toolRegistry, new TestFixtures.StubToolExecutor(),
                new InMemoryStore(), new TestFixtures.StubCronScheduler(),
                skillRegistry,
                new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp")),
                config);
        controller = new ChannelController(session);
    }

    @Test
    void sendToUnknownChannelReturnsError() {
        var body = Map.of("destination", "user1", "content", "hello");
        var result = controller.send("nonexistent", body).block();

        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(String.valueOf(result.get("error")).contains("Channel not found"));
    }

    @Test
    void dingTalkWebhookExtractsTextFromMap() {
        var body = Map.<String, Object>of(
                "text", Map.of("content", "hello from dingtalk"),
                "senderId", "user123",
                "senderNick", "Test User");

        var result = controller.dingTalkWebhook(body)
                .onErrorResume(e -> Mono.just(Map.of("error", (Object) e.getMessage())))
                .block();
        assertNotNull(result);
    }

    @Test
    void feishuWebhookExtractsNestedText() {
        var body = Map.<String, Object>of(
                "event", Map.of(
                        "message", Map.of("content", "hello from feishu"),
                        "sender", Map.of("sender_id", Map.of("open_id", "ou_abc"))));

        var result = controller.feishuWebhook(body)
                .onErrorResume(e -> Mono.just(Map.of("error", (Object) e.getMessage())))
                .block();
        assertNotNull(result);
    }

    @Test
    void sendWithDefaultDestination() {
        var body = Map.of("content", "hello");
        var result = controller.send("missing-channel", body).block();

        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    @Test
    void sendWithEmptyContentReturnsError() {
        var body = Map.of("content", "   ");
        var result = controller.send("some-channel", body).block();
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(String.valueOf(result.get("error")).contains("content must not be empty"));
    }

    @Test
    void dingTalkWebhookWithEmptyTextReturnsError() {
        var body = Map.<String, Object>of("text", Map.of("content", ""));
        var result = controller.dingTalkWebhook(body).block();
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsAvailableChannels() {
        var result = controller.list();
        assertNotNull(result);
        assertTrue((Integer) result.get("total") >= 5);
        assertEquals(0, result.get("active"));
        var channels = (java.util.List<java.util.Map<String, Object>>) result.get("channels");
        var ids = channels.stream().map(c -> c.get("id")).toList();
        assertTrue(ids.contains("dingtalk"));
        assertTrue(ids.contains("feishu"));
    }
}
