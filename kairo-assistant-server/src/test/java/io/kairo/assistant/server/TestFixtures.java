package io.kairo.assistant.server;

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
import io.kairo.assistant.gateway.AgentSessionPool;
import io.kairo.assistant.gateway.ModelRegistry;
import io.kairo.assistant.gateway.ModelSwitchService;
import io.kairo.assistant.gateway.UnifiedGateway;
import io.kairo.assistant.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.cron.CronScheduler;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class TestFixtures {

    private TestFixtures() {}

    static AssistantConfig defaultConfig() {
        return AssistantConfig.builder()
                .apiKey("test-key")
                .modelProvider("anthropic")
                .modelName("claude-test")
                .dataDir(System.getProperty("java.io.tmpdir"))
                .build();
    }

    static AssistantSession defaultSession() {
        return defaultSession(new StubToolExecutor(), new StubCronScheduler());
    }

    static AssistantSession defaultSession(ToolExecutor toolExecutor, CronScheduler cronScheduler) {
        var toolRegistry = new DefaultToolRegistry();
        var skillRegistry = AssistantSkills.createRegistry();
        return new AssistantSession(
                new StubAgent(), toolRegistry, toolExecutor,
                new InMemoryStore(), cronScheduler,
                skillRegistry,
                new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp")),
                defaultConfig());
    }

    static UnifiedGateway stubGateway() {
        var pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> new StubAgent(), null);
        return new UnifiedGateway(pool);
    }

    static UnifiedGateway stubGateway(Agent agent) {
        var pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> agent, null);
        return new UnifiedGateway(pool);
    }

    static ModelSwitchService stubModelSwitchService(UnifiedGateway gateway) {
        return new ModelSwitchService(gateway, new ModelRegistry(), defaultConfig());
    }

    static class StubAgent implements Agent {
        @Override public Mono<Msg> call(Msg input) {
            return Mono.just(Msg.of(MsgRole.ASSISTANT, "echo: " + input.text()));
        }
        @Override public String id() { return "test"; }
        @Override public String name() { return "Test"; }
        @Override public AgentState state() { return AgentState.IDLE; }
        @Override public void interrupt() {}
    }

    static class StubToolExecutor implements ToolExecutor {
        @Override public Mono<ToolResult> execute(String name, Map<String, Object> input) { return Mono.empty(); }
        @Override public Mono<ToolResult> execute(String name, Map<String, Object> input, Duration t) { return Mono.empty(); }
        @Override public Flux<ToolResult> executeParallel(List<ToolInvocation> inv) { return Flux.empty(); }
    }

    static class StubCronScheduler implements CronScheduler {
        @Override public CronTask create(String c, String p, boolean r, boolean d) { return null; }
        @Override public boolean delete(String id) { return false; }
        @Override public List<CronTask> list() { return List.of(); }
        @Override public void start() {}
        @Override public void stop() {}
    }
}
