package io.kairo.assistant.server;

import io.kairo.assistant.agent.AssistantSession;
import io.kairo.core.session.UnifiedGateway;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final Instant startTime = Instant.now();
    private final AssistantSession session;
    private final UnifiedGateway gateway;

    public HealthController(AssistantSession session, UnifiedGateway gateway) {
        this.session = session;
        this.gateway = gateway;
    }

    // Bare-root health endpoints are mapped under /healthz/* (K8s liveness/
    // readiness convention) so they don't shadow the Console's React Router
    // routes at /health. The /api/health/* set still exists in StatusController
    // for the UI and any callers preferring the /api prefix.
    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/healthz/detailed")
    public Map<String, Object> healthDetailed() {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "ok");
        result.put("uptime", formatDuration(Duration.between(startTime, Instant.now())));
        result.put("uptimeSeconds", Duration.between(startTime, Instant.now()).getSeconds());

        var runtime = Runtime.getRuntime();
        var mem = new LinkedHashMap<String, Object>();
        mem.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        mem.put("heapMaxMb", runtime.maxMemory() / (1024 * 1024));
        mem.put("heapUsagePercent", Math.round(
                (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory() * 100));
        result.put("memory", mem);

        var pool = new LinkedHashMap<String, Object>();
        pool.put("activeSessions", gateway.pool().size());
        pool.put("maxSessions", session.config().sessionPoolSize());
        result.put("sessionPool", pool);

        var model = new LinkedHashMap<String, Object>();
        model.put("provider", session.config().modelProvider());
        model.put("name", session.config().modelName());
        result.put("model", model);

        result.put("jvm", Map.of(
                "version", System.getProperty("java.version"),
                "availableProcessors", runtime.availableProcessors(),
                "uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime()));

        return result;
    }

    @GetMapping("/v1/healthz")
    public Map<String, String> healthV1() {
        return Map.of("status", "ok");
    }

    private String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }
}
