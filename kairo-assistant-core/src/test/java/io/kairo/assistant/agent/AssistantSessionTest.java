package io.kairo.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.cron.CronTask;
import io.kairo.api.message.Msg;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolInvocation;
import io.kairo.api.tool.ToolRegistry;
import io.kairo.api.tool.ToolResult;
import io.kairo.assistant.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.cron.CronScheduler;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AssistantSessionTest {

    @Test
    void recordFieldsAreAccessible() {
        var session = createSession();
        assertThat(session.agent()).isNotNull();
        assertThat(session.toolRegistry()).isInstanceOf(ToolRegistry.class);
        assertThat(session.toolExecutor()).isNotNull();
        assertThat(session.memoryStore()).isNotNull();
        assertThat(session.cronScheduler()).isNotNull();
        assertThat(session.skillRegistry()).isNotNull();
        assertThat(session.pluginManager()).isNotNull();
        assertThat(session.config()).isNotNull();
    }

    @Test
    void dependenciesIncludesMemoryAndCron() {
        var session = createSession();
        var deps = session.dependencies();
        assertThat(deps).containsKey("memoryStore");
        assertThat(deps).containsKey("cronScheduler");
    }

    @Test
    void dependenciesExcludesNulls() {
        var config = AssistantConfig.builder().apiKey("test").build();
        var session = new AssistantSession(
                new StubAgent(), new DefaultToolRegistry(),
                new StubToolExecutor(), null, new StubCronScheduler(),
                AssistantSkills.createRegistry(),
                new PluginManager(new DefaultToolRegistry(), AssistantSkills.createRegistry(), Path.of("/tmp")),
                config);
        var deps = session.dependencies();
        assertThat(deps).doesNotContainKey("memoryStore");
        assertThat(deps).containsKey("cronScheduler");
    }

    @Test
    void startAndStopLifecycle() {
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        CronScheduler cron = new StubCronScheduler() {
            @Override
            public void start() { started.set(true); }
            @Override
            public void stop() { stopped.set(true); }
        };

        Agent agent = new StubAgent() {
            @Override
            public void interrupt() { interrupted.set(true); }
        };

        var config = AssistantConfig.builder().apiKey("test").build();
        var session = new AssistantSession(
                agent, new DefaultToolRegistry(),
                new StubToolExecutor(), new InMemoryStore(), cron,
                AssistantSkills.createRegistry(),
                new PluginManager(new DefaultToolRegistry(), AssistantSkills.createRegistry(), Path.of("/tmp")),
                config);

        session.start();
        assertThat(started.get()).isTrue();

        session.stop();
        assertThat(stopped.get()).isTrue();
        assertThat(interrupted.get()).isTrue();
    }

    @Test
    void startLoadsPlugins() {
        AtomicBoolean loaded = new AtomicBoolean(false);
        var toolRegistry = new DefaultToolRegistry();
        var skillRegistry = AssistantSkills.createRegistry();
        var pm = new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp"));
        var config = AssistantConfig.builder().apiKey("test").build();

        var session = new AssistantSession(
                new StubAgent(), toolRegistry,
                new StubToolExecutor(), new InMemoryStore(), new StubCronScheduler(),
                skillRegistry, pm, config);

        session.start();
        // loadPlugins was called (no exception thrown)
        assertThat(pm.plugins()).isEmpty();
    }

    @Test
    void stopUnloadsPlugins() {
        var toolRegistry = new DefaultToolRegistry();
        var skillRegistry = AssistantSkills.createRegistry();
        var pm = new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp"));
        var config = AssistantConfig.builder().apiKey("test").build();

        var session = new AssistantSession(
                new StubAgent(), toolRegistry,
                new StubToolExecutor(), new InMemoryStore(), new StubCronScheduler(),
                skillRegistry, pm, config);

        session.start();
        session.stop();
        assertThat(pm.plugins()).isEmpty();
    }

    @Test
    void configRetainsValues() {
        var config = AssistantConfig.builder()
                .modelProvider("openai")
                .modelName("gpt-4o")
                .apiKey("test-key")
                .dataDir("/tmp/test")
                .maxIterations(50)
                .timeout(Duration.ofMinutes(5))
                .build();

        var session = createSessionWith(config);
        assertThat(session.config().modelProvider()).isEqualTo("openai");
        assertThat(session.config().modelName()).isEqualTo("gpt-4o");
        assertThat(session.config().dataDir()).isEqualTo("/tmp/test");
    }

    private AssistantSession createSession() {
        var config = AssistantConfig.builder().apiKey("test").build();
        return createSessionWith(config);
    }

    private AssistantSession createSessionWith(AssistantConfig config) {
        var toolRegistry = new DefaultToolRegistry();
        return new AssistantSession(
                new StubAgent(), toolRegistry,
                new StubToolExecutor(), new InMemoryStore(), new StubCronScheduler(),
                AssistantSkills.createRegistry(),
                new PluginManager(toolRegistry, AssistantSkills.createRegistry(), Path.of("/tmp")),
                config);
    }

    private static class StubAgent implements Agent {
        @Override
        public Mono<Msg> call(Msg input) { return Mono.empty(); }
        @Override
        public String id() { return "test-agent"; }
        @Override
        public String name() { return "Test Agent"; }
        @Override
        public AgentState state() { return AgentState.IDLE; }
        @Override
        public void interrupt() {}
    }

    private static class StubToolExecutor implements ToolExecutor {
        @Override
        public Mono<ToolResult> execute(String toolName, Map<String, Object> input) { return Mono.empty(); }
        @Override
        public Mono<ToolResult> execute(String toolName, Map<String, Object> input, Duration timeout) { return Mono.empty(); }
        @Override
        public Flux<ToolResult> executeParallel(List<ToolInvocation> invocations) { return Flux.empty(); }
    }

    private static class StubCronScheduler implements CronScheduler {
        @Override
        public CronTask create(String cron, String prompt, boolean recurring, boolean durable) { return null; }
        @Override
        public boolean delete(String taskId) { return false; }
        @Override
        public List<CronTask> list() { return List.of(); }
        @Override
        public void start() {}
        @Override
        public void stop() {}
    }
}
