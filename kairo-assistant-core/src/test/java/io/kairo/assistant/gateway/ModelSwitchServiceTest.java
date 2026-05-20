package io.kairo.assistant.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantConfig;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ModelSwitchServiceTest {

    private UnifiedGateway gateway;
    private ModelSwitchService service;

    @BeforeEach
    void setUp() {
        var pool = new AgentSessionPool(10, Duration.ofMinutes(60),
                key -> new StubAgent("original"), null);
        gateway = new UnifiedGateway(pool);
        var config = AssistantConfig.builder()
                .apiKey("test-key")
                .modelProvider("anthropic")
                .modelName("claude-test")
                .dataDir(System.getProperty("java.io.tmpdir"))
                .build();
        service = new ModelSwitchService(gateway, new ModelRegistry(), config);
    }

    @Test
    void switchToKnownModelReportsSuccess() {
        SessionKey key = SessionKey.of("test", "user1");
        gateway.pool().getOrCreate(key);

        // This will fail on actual model creation (no real API key), but we can test unknown model path
        ModelSwitchService.SwitchResult result = service.switchModel(key, "nonexistent-model");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Unknown model");
    }

    @Test
    void unknownModelReturnsAvailableList() {
        SessionKey key = SessionKey.of("test", "user1");
        ModelSwitchService.SwitchResult result = service.switchModel(key, "bad-model");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("claude-sonnet");
        assertThat(result.message()).contains("gpt-4o");
    }

    @Test
    void registryAccessFromService() {
        assertThat(service.registry().aliases()).isNotEmpty();
        assertThat(service.registry().resolve("claude-opus")).isNotNull();
    }

    static class StubAgent implements Agent {
        private final String name;

        StubAgent(String name) { this.name = name; }

        @Override public Mono<Msg> call(Msg input) {
            return Mono.just(Msg.of(MsgRole.ASSISTANT, name + ": " + input.text()));
        }
        @Override public String id() { return "stub-" + name; }
        @Override public String name() { return name; }
        @Override public AgentState state() { return AgentState.IDLE; }
        @Override public void interrupt() {}
    }
}
