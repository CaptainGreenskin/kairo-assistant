package io.kairo.assistant.server;

import io.kairo.assistant.agent.AssistantSession;
import io.kairo.core.session.UnifiedGateway;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GracefulShutdownHandler {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);
    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 30_000;

    private final AssistantSession session;
    private final UnifiedGateway gateway;
    private final SessionManager sessionManager;
    private final MetricsCollector metrics;
    private final AssistantWebSocketHandler wsHandler;
    private final long drainTimeoutMs;

    public GracefulShutdownHandler(AssistantSession session, UnifiedGateway gateway,
                                   SessionManager sessionManager, MetricsCollector metrics,
                                   AssistantWebSocketHandler wsHandler) {
        this.session = session;
        this.gateway = gateway;
        this.sessionManager = sessionManager;
        this.metrics = metrics;
        this.wsHandler = wsHandler;
        this.drainTimeoutMs = parseDrainTimeout();
    }

    @PreDestroy
    public void onShutdown() {
        Instant start = Instant.now();
        log.info("Graceful shutdown initiated at {}", start);

        gateway.startDrain();
        notifyWebSocketClients();

        int inflight = gateway.activeRequestCount();
        if (inflight > 0) {
            log.info("Draining {} in-flight request(s), timeout={}ms", inflight, drainTimeoutMs);
            boolean drained = gateway.awaitDrain(drainTimeoutMs);
            if (drained) {
                log.info("All in-flight requests completed");
            } else {
                int remaining = gateway.activeRequestCount();
                log.warn("Drain timeout reached, {} request(s) still active — interrupting", remaining);
                interruptAllSessions();
            }
        }

        saveClientSessions();

        log.info("Shutdown metrics: messages={}, tokens={}, agentCalls={}",
                metrics.messageCount(), metrics.totalTokens(),
                metrics.toPrometheus().lines()
                        .filter(l -> l.startsWith("kairo_agent_calls_total"))
                        .findFirst().orElse("N/A"));

        try {
            gateway.shutdown();
            session.stop();
            log.info("Session stopped (cron scheduler, plugins)");
        } catch (Exception e) {
            log.warn("Failed to stop session: {}", e.getMessage());
        }

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Graceful shutdown complete in {}ms", elapsed.toMillis());
    }

    private void notifyWebSocketClients() {
        try {
            wsHandler.broadcastShutdown();
        } catch (Exception e) {
            log.debug("Failed to notify WebSocket clients of shutdown: {}", e.getMessage());
        }
    }

    private void interruptAllSessions() {
        try {
            session.agent().interrupt();
        } catch (Exception e) {
            log.debug("Failed to interrupt primary agent: {}", e.getMessage());
        }
    }

    private void saveClientSessions() {
        int activeSessions = sessionManager.activeCount();
        if (activeSessions > 0) {
            log.info("Saving {} active client session(s)", activeSessions);
            sessionManager.all().forEach((id, clientSession) -> {
                try {
                    if (clientSession.messageCount() > 0) {
                        clientSession.conversationStore().endSession();
                    }
                } catch (Exception e) {
                    log.warn("Failed to save conversation for {}: {}", id, e.getMessage());
                }
            });
        }
    }

    private static long parseDrainTimeout() {
        String val = System.getenv("KAIRO_DRAIN_TIMEOUT_MS");
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException e) {
                LoggerFactory.getLogger(GracefulShutdownHandler.class)
                        .warn("Invalid KAIRO_DRAIN_TIMEOUT_MS '{}', using default", val);
            }
        }
        return DEFAULT_DRAIN_TIMEOUT_MS;
    }
}
