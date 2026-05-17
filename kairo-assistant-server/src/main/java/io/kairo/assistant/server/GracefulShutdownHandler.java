package io.kairo.assistant.server;

import io.kairo.assistant.agent.AssistantSession;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GracefulShutdownHandler {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final AssistantSession session;
    private final SessionManager sessionManager;
    private final MetricsCollector metrics;

    public GracefulShutdownHandler(AssistantSession session, SessionManager sessionManager,
                                   MetricsCollector metrics) {
        this.session = session;
        this.sessionManager = sessionManager;
        this.metrics = metrics;
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Graceful shutdown initiated at {}", Instant.now());

        try {
            session.agent().interrupt();
            log.info("Agent interrupted");
        } catch (Exception e) {
            log.warn("Failed to interrupt agent: {}", e.getMessage());
        }

        int activeSessions = sessionManager.activeCount();
        if (activeSessions > 0) {
            log.info("Closing {} active client session(s)", activeSessions);
        }

        log.info("Shutdown metrics: messages={}, tokens={}, agentCalls={}",
                metrics.messageCount(), metrics.totalTokens(),
                metrics.toPrometheus().lines()
                        .filter(l -> l.startsWith("kairo_agent_calls_total"))
                        .findFirst().orElse("N/A"));

        try {
            session.stop();
            log.info("Session stopped (cron scheduler, plugins)");
        } catch (Exception e) {
            log.warn("Failed to stop session: {}", e.getMessage());
        }

        log.info("Graceful shutdown complete");
    }
}
