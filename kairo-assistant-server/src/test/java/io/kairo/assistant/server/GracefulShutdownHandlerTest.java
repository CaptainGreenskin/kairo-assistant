package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.agent.Agent;
import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.cron.CronScheduler;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GracefulShutdownHandlerTest {

    @Test
    void shutdownInterruptsAgentAndStopsSession() {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean cronStopped = new AtomicBoolean(false);

        Agent agent = new TestFixtures.StubAgent() {
            @Override public void interrupt() { interrupted.set(true); }
        };
        CronScheduler cron = new TestFixtures.StubCronScheduler() {
            @Override public void stop() { cronStopped.set(true); }
        };

        var session = createSession(agent, cron);
        var handler = new GracefulShutdownHandler(session, new SessionManager(session), new MetricsCollector());
        handler.onShutdown();

        assertTrue(interrupted.get());
        assertTrue(cronStopped.get());
    }

    @Test
    void shutdownHandlesAgentInterruptException() {
        Agent agent = new TestFixtures.StubAgent() {
            @Override public void interrupt() { throw new RuntimeException("interrupt failed"); }
        };
        var session = createSession(agent, new TestFixtures.StubCronScheduler());
        var handler = new GracefulShutdownHandler(session, new SessionManager(session), new MetricsCollector());
        assertDoesNotThrow(handler::onShutdown);
    }

    @Test
    void shutdownHandlesSessionStopException() {
        CronScheduler cron = new TestFixtures.StubCronScheduler() {
            @Override public void stop() { throw new RuntimeException("stop failed"); }
        };
        var session = createSession(new TestFixtures.StubAgent(), cron);
        var handler = new GracefulShutdownHandler(session, new SessionManager(session), new MetricsCollector());
        assertDoesNotThrow(handler::onShutdown);
    }

    @Test
    void interruptCalledMultipleTimes() {
        AtomicInteger interruptCount = new AtomicInteger(0);
        Agent agent = new TestFixtures.StubAgent() {
            @Override public void interrupt() { interruptCount.incrementAndGet(); }
        };
        var session = createSession(agent, new TestFixtures.StubCronScheduler());
        new GracefulShutdownHandler(session, new SessionManager(session), new MetricsCollector()).onShutdown();
        assertTrue(interruptCount.get() >= 1);
    }

    private AssistantSession createSession(Agent agent, CronScheduler cron) {
        var config = AssistantConfig.builder().apiKey("test").build();
        var toolRegistry = new DefaultToolRegistry();
        var skillRegistry = AssistantSkills.createRegistry();
        return new AssistantSession(
                agent, toolRegistry, new TestFixtures.StubToolExecutor(),
                new InMemoryStore(), cron, skillRegistry,
                new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp")),
                config);
    }
}
