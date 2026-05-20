package io.kairo.assistant.server;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.ConversationStore;
import io.kairo.assistant.tool.ToolCallLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class StatusController {

    private final AssistantSession session;
    private final ConversationStore conversationStore;
    private final MetricsCollector metrics;
    private final SessionManager sessionManager;

    public StatusController(AssistantSession session, MetricsCollector metrics,
                            SessionManager sessionManager) {
        this.session = session;
        this.metrics = metrics;
        this.sessionManager = sessionManager;
        this.conversationStore = new ConversationStore(
                Path.of(session.config().dataDir(), "conversations", "web"));
    }

    private final Instant startTime = Instant.now();

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "running");
        result.put("provider", session.config().modelProvider());
        result.put("model", session.config().modelName());
        result.put("toolCount", session.toolRegistry().getAll().size());
        result.put("skillCount", session.skillRegistry().list().size());
        result.put("pluginCount", session.pluginManager().list().size());
        return result;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("uptime", java.time.Duration.between(startTime, Instant.now()).toSeconds());
        result.put("version", "0.1.0");
        result.put("agentState", session.agent().state().name());
        result.put("activeSessions", sessionManager.activeCount());

        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        result.put("memoryUsedMB", usedMB);
        result.put("memoryMaxMB", maxMB);

        Map<String, String> components = new LinkedHashMap<>();
        components.put("agent", "ok");
        components.put("tools", session.toolRegistry().getAll().isEmpty() ? "degraded" : "ok");
        components.put("skills", "ok");
        result.put("components", components);
        return result;
    }

    @GetMapping("/health/live")
    public Map<String, Object> liveness() {
        return Map.of("status", "ok");
    }

    @GetMapping("/health/ready")
    public Map<String, Object> readiness() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean toolsReady = !session.toolRegistry().getAll().isEmpty();
        boolean agentReady = session.agent() != null;
        boolean allReady = toolsReady && agentReady;

        result.put("status", allReady ? "ok" : "not_ready");
        result.put("checks", Map.of(
                "agent", agentReady ? "ok" : "not_ready",
                "tools", toolsReady ? "ok" : "not_ready"));
        return result;
    }

    @GetMapping("/health/detailed")
    public Map<String, Object> detailedHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("uptime", java.time.Duration.between(startTime, Instant.now()).toSeconds());
        result.put("version", "0.1.0");
        result.put("timestamp", Instant.now().toString());

        Runtime rt = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMB", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        memory.put("totalMB", rt.totalMemory() / (1024 * 1024));
        memory.put("maxMB", rt.maxMemory() / (1024 * 1024));
        memory.put("freePercent", (int) (100.0 * rt.freeMemory() / rt.totalMemory()));
        result.put("memory", memory);

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("javaVersion", System.getProperty("java.version"));
        runtime.put("javaVendor", System.getProperty("java.vendor"));
        runtime.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        runtime.put("processors", rt.availableProcessors());
        runtime.put("activeThreads", Thread.activeCount());
        result.put("runtime", runtime);

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("agent", Map.of("status", "ok",
                "provider", session.config().modelProvider(),
                "model", session.config().modelName()));
        components.put("tools", Map.of("status", session.toolRegistry().getAll().isEmpty() ? "degraded" : "ok",
                "count", session.toolRegistry().getAll().size()));
        components.put("skills", Map.of("status", "ok",
                "count", session.skillRegistry().list().size()));
        components.put("plugins", Map.of("status", "ok",
                "count", session.pluginManager().list().size()));
        result.put("components", components);

        result.put("metrics", Map.of(
                "totalRequests", metrics.toPrometheus().lines()
                        .filter(l -> l.startsWith("kairo_requests_total"))
                        .findFirst().orElse("N/A")));

        return result;
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("os", System.getProperty("os.name"));
        result.put("osVersion", System.getProperty("os.version"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("processors", rt.availableProcessors());
        result.put("memoryUsedMB", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        result.put("memoryTotalMB", rt.totalMemory() / (1024 * 1024));
        result.put("memoryMaxMB", rt.maxMemory() / (1024 * 1024));
        result.put("activeThreads", Thread.activeCount());
        result.put("userDir", System.getProperty("user.dir"));
        result.put("userHome", System.getProperty("user.home"));
        result.put("fileEncoding", System.getProperty("file.encoding"));
        return result;
    }

    @GetMapping("/agent/state")
    public Map<String, Object> agentState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", session.agent().state().name());
        result.put("agentId", session.agent().id());
        result.put("agentName", session.agent().name());
        return result;
    }

    @GetMapping("/tools")
    public List<Map<String, String>> tools() {
        return session.toolRegistry().getAll().stream()
                .map(tool -> Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "category", tool.category().name(),
                        "sideEffect", tool.sideEffect().name()))
                .toList();
    }

    @GetMapping("/tools/categories")
    public Map<String, Object> toolCategories() {
        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
        session.toolRegistry().getAll().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(tool -> {
                    String cat = tool.category().name();
                    grouped.computeIfAbsent(cat, k -> new java.util.ArrayList<>())
                            .add(Map.of("name", tool.name(), "description", tool.description()));
                });
        Map<String, Object> result = new LinkedHashMap<>();
        grouped.forEach((cat, tools) -> {
            Map<String, Object> catInfo = new LinkedHashMap<>();
            catInfo.put("count", tools.size());
            catInfo.put("tools", tools);
            result.put(cat, catInfo);
        });
        return result;
    }

    @GetMapping("/tools/search")
    public Map<String, Object> toolSearch(@RequestParam String q) {
        String query = q.toLowerCase();
        var matches = session.toolRegistry().getAll().stream()
                .filter(t -> t.name().toLowerCase().contains(query)
                        || t.description().toLowerCase().contains(query))
                .map(tool -> Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "category", tool.category().name()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", q);
        result.put("count", matches.size());
        result.put("tools", matches);
        return result;
    }

    @GetMapping("/tools/{name}")
    public Map<String, Object> toolDetail(@PathVariable String name) {
        return session.toolRegistry().getAll().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .map(tool -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("name", tool.name());
                    result.put("description", tool.description());
                    result.put("category", tool.category().name());
                    result.put("sideEffect", tool.sideEffect().name());
                    if (tool.timeout() != null) {
                        result.put("timeoutSeconds", tool.timeout().toSeconds());
                    }
                    if (tool.usageGuidance() != null) {
                        result.put("usageGuidance", tool.usageGuidance());
                    }
                    if (tool.inputSchema() != null) {
                        result.put("inputSchema", schemaToMap(tool.inputSchema()));
                    }
                    return result;
                })
                .orElse(Map.of("error", "Tool not found: " + name));
    }

    private Map<String, Object> schemaToMap(io.kairo.api.tool.JsonSchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", schema.type());
        if (schema.description() != null) result.put("description", schema.description());
        if (schema.required() != null && !schema.required().isEmpty()) {
            result.put("required", schema.required());
        }
        if (schema.properties() != null && !schema.properties().isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            schema.properties().forEach((k, v) -> props.put(k, schemaToMap(v)));
            result.put("properties", props);
        }
        return result;
    }

    @GetMapping("/tools/history")
    public Map<String, Object> toolHistory(@RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (session.toolExecutor() instanceof ToolCallLogger logger) {
            result.put("totalCalls", logger.totalCalls());
            result.put("totalErrors", logger.totalErrors());
            result.put("avgDurationMs", logger.averageDurationMs());
            result.put("calls", logger.recentCalls(limit).stream().map(r -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("tool", r.toolName());
                entry.put("timestamp", r.timestamp().toString());
                entry.put("durationMs", r.durationMs());
                entry.put("success", r.success());
                if (r.error() != null) entry.put("error", r.error());
                return entry;
            }).toList());
        } else {
            result.put("totalCalls", 0);
            result.put("calls", List.of());
            result.put("note", "ToolCallLogger not active");
        }
        return result;
    }

    @PostMapping("/tools/execute")
    public Mono<Map<String, Object>> executeTool(@RequestBody Map<String, Object> body) {
        String toolName = (String) body.get("tool");
        if (toolName == null || toolName.isBlank()) {
            return Mono.just(Map.of("error", "tool name is required"));
        }

        boolean exists = session.toolRegistry().getAll().stream()
                .anyMatch(t -> t.name().equals(toolName));
        if (!exists) {
            return Mono.just(Map.of("error", "unknown tool: " + toolName));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> args = body.get("args") instanceof Map
                ? (Map<String, Object>) body.get("args") : Map.of();

        return session.toolExecutor().execute(toolName, args)
                .map(result -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("tool", toolName);
                    resp.put("success", !result.isError());
                    resp.put("content", result.content());
                    return resp;
                })
                .onErrorResume(e -> Mono.just(Map.of(
                        "tool", toolName, "error", e.getMessage())));
    }

    @PostMapping("/tools/batch")
    public Mono<Map<String, Object>> batchExecuteTools(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = body.get("calls") instanceof List<?> list
                ? list.stream()
                    .filter(e -> e instanceof Map)
                    .map(e -> (Map<String, Object>) e)
                    .toList()
                : List.of();

        if (calls.isEmpty()) {
            return Mono.just(Map.of("error", "'calls' array is required with at least one tool invocation"));
        }

        List<io.kairo.api.tool.ToolInvocation> invocations = new java.util.ArrayList<>();
        for (Map<String, Object> call : calls) {
            String toolName = (String) call.get("tool");
            if (toolName == null || toolName.isBlank()) {
                return Mono.just(Map.of("error", "each call must have a 'tool' name"));
            }
            boolean exists = session.toolRegistry().getAll().stream()
                    .anyMatch(t -> t.name().equals(toolName));
            if (!exists) {
                return Mono.just(Map.of("error", "unknown tool: " + toolName));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> args = call.get("args") instanceof Map
                    ? (Map<String, Object>) call.get("args") : Map.of();
            invocations.add(new io.kairo.api.tool.ToolInvocation(toolName, args));
        }

        return reactor.core.publisher.Flux.fromIterable(invocations)
                .flatMap(inv -> session.toolExecutor().execute(inv.toolName(), inv.input())
                        .map(result -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("tool", inv.toolName());
                            entry.put("success", !result.isError());
                            entry.put("content", result.content());
                            return entry;
                        })
                        .onErrorResume(e -> Mono.just(Map.<String, Object>of(
                                "tool", inv.toolName(), "success", false, "content", e.getMessage()))))
                .collectList()
                .map(results -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("total", results.size());
                    resp.put("results", results);
                    return resp;
                });
    }

    @GetMapping("/context")
    public Map<String, Object> getContext() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (session.agent() instanceof io.kairo.core.agent.DefaultReActAgent reactAgent) {
            var history = reactAgent.conversationHistory();
            result.put("messageCount", history.size());
            result.put("totalTokens", reactAgent.totalTokensUsed());
            result.put("state", reactAgent.state().name());
            result.put("messages", history.stream()
                    .map(msg -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("role", msg.role().name().toLowerCase());
                        String text = msg.text();
                        if (text.length() > 200) text = text.substring(0, 197) + "...";
                        m.put("preview", text);
                        return m;
                    }).toList());
        } else {
            result.put("state", session.agent().state().name());
            result.put("note", "detailed context not available for this agent type");
        }
        return result;
    }

    @DeleteMapping("/context")
    public Map<String, String> clearContext() {
        if (session.agent() instanceof io.kairo.core.agent.DefaultReActAgent reactAgent) {
            reactAgent.conversationHistory().clear();
            return Map.of("status", "cleared");
        }
        return Map.of("error", "context clear not supported for this agent type");
    }

    @GetMapping(value = "/metrics", produces = "text/plain")
    public String metrics() {
        return metrics.toPrometheus();
    }

    @GetMapping("/sessions")
    public List<Map<String, String>> sessions() {
        return conversationStore.listSessions();
    }

    @GetMapping("/sessions/search")
    public Map<String, Object> searchSessions(@RequestParam String q,
                                              @RequestParam(defaultValue = "50") int limit) {
        var results = conversationStore.search(q);
        var limited = results.size() > limit ? results.subList(0, limit) : results;
        return Map.of(
                "query", q,
                "total", results.size(),
                "results", limited);
    }

    @GetMapping("/sessions/{sessionId}")
    public List<Map<String, Object>> sessionHistory(@PathVariable String sessionId) {
        return conversationStore.loadSession(sessionId);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        var c = session.config();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelProvider", c.modelProvider());
        result.put("modelName", c.modelName());
        result.put("apiKey", maskKey(c.apiKey()));
        result.put("apiBaseUrl", c.apiBaseUrl());
        result.put("maxIterations", c.maxIterations());
        result.put("timeout", c.timeout().toSeconds() + "s");
        result.put("tokenBudget", c.tokenBudget());
        result.put("dataDir", c.dataDir());
        return result;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "***";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uptime", java.time.Duration.between(startTime, Instant.now()).toSeconds());

        var sessions = conversationStore.listSessions();
        result.put("totalSessions", sessions.size());

        if (session.toolExecutor() instanceof ToolCallLogger logger) {
            Map<String, Object> toolStats = new LinkedHashMap<>();
            toolStats.put("totalCalls", logger.totalCalls());
            toolStats.put("totalErrors", logger.totalErrors());
            toolStats.put("avgDurationMs", logger.averageDurationMs());
            toolStats.put("totalDurationMs", logger.totalDurationMs());
            result.put("tools", toolStats);
        }

        result.put("registeredTools", session.toolRegistry().getAll().size());
        result.put("registeredSkills", session.skillRegistry().list().size());
        result.put("plugins", session.pluginManager().list().size());
        result.put("provider", session.config().modelProvider());
        result.put("model", session.config().modelName());

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("inputTokens", metrics.inputTokens());
        tokens.put("outputTokens", metrics.outputTokens());
        tokens.put("totalTokens", metrics.totalTokens());
        result.put("tokens", tokens);
        result.put("totalMessages", metrics.messageCount());
        result.put("durationPercentiles", metrics.durationPercentiles());

        var endpoints = metrics.allEndpointHits();
        if (!endpoints.isEmpty()) {
            result.put("endpointHits", endpoints);
        }

        return result;
    }

    @GetMapping("/analytics/endpoints")
    public Map<String, Object> endpointAnalytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpoints", metrics.allEndpointHits());
        return result;
    }

    @GetMapping("/analytics/tools")
    public Map<String, Object> toolAnalytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        var stats = metrics.toolCallStats();
        result.put("totalToolCalls", stats.values().stream().mapToLong(Long::longValue).sum());
        result.put("uniqueToolsUsed", stats.size());
        result.put("tools", stats);
        return result;
    }

    @GetMapping("/analytics/tokens")
    public Map<String, Object> tokenAnalytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        long input = metrics.inputTokens();
        long output = metrics.outputTokens();
        long total = input + output;
        result.put("inputTokens", input);
        result.put("outputTokens", output);
        result.put("totalTokens", total);

        long msgs = metrics.messageCount();
        result.put("totalMessages", msgs);
        if (msgs > 0) {
            result.put("avgTokensPerMessage", total / msgs);
        }

        double inputCostPer1M = 3.0;
        double outputCostPer1M = 15.0;
        double estimatedCost = (input / 1_000_000.0) * inputCostPer1M
                + (output / 1_000_000.0) * outputCostPer1M;
        result.put("estimatedCostUsd", Math.round(estimatedCost * 10000.0) / 10000.0);
        result.put("pricing", Map.of(
                "model", session.config().modelName(),
                "inputPer1MTokens", inputCostPer1M,
                "outputPer1MTokens", outputCostPer1M,
                "note", "Estimates based on Claude Sonnet pricing; actual may vary"));
        return result;
    }

    @GetMapping("/analytics/latency")
    public Map<String, Object> latencyAnalytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("percentiles", metrics.durationPercentiles());

        long calls = 0;
        long totalDuration = 0;
        if (session.toolExecutor() instanceof ToolCallLogger logger) {
            calls = logger.totalCalls();
            totalDuration = logger.totalDurationMs();
        }
        result.put("totalAgentCalls", calls);
        result.put("totalDurationMs", totalDuration);
        if (calls > 0) {
            result.put("avgDurationMs", totalDuration / calls);
        }
        return result;
    }

    @PostMapping("/summarize")
    public Mono<Map<String, Object>> summarize(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.just(Map.of("error", "sessionId is required"));
        }
        var messages = conversationStore.loadSession(sessionId);
        if (messages.isEmpty()) {
            return Mono.just(Map.of("error", "session not found or empty"));
        }
        StringBuilder transcript = new StringBuilder();
        for (var msg : messages) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String text = String.valueOf(msg.getOrDefault("text", ""));
            if (!text.isBlank()) {
                transcript.append(role).append(": ").append(text, 0, Math.min(text.length(), 500)).append("\n");
            }
        }
        String prompt = "Summarize this conversation in 2-3 sentences:\n\n" + transcript;
        return session.agent().call(Msg.of(MsgRole.USER, prompt))
                .map(response -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("sessionId", sessionId);
                    result.put("summary", response.text());
                    result.put("messageCount", messages.size());
                    return result;
                })
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        boolean deleted = conversationStore.deleteSession(sessionId);
        if (deleted) {
            return Map.of("status", "deleted", "sessionId", sessionId);
        }
        return Map.of("error", "session not found", "sessionId", sessionId);
    }

    @GetMapping("/sessions/{sessionId}/export")
    public Map<String, Object> exportSession(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "markdown") String format) {
        String exported = conversationStore.exportSession(sessionId, format);
        if (exported == null) {
            return Map.of("error", "session not found or empty");
        }
        return Map.of("sessionId", sessionId, "format", format, "content", exported);
    }

    @PutMapping("/sessions/{sessionId}/title")
    public Map<String, Object> renameSession(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return Map.of("error", "title is required");
        }
        boolean renamed = conversationStore.setTitle(sessionId, title);
        if (renamed) {
            return Map.of("status", "ok", "sessionId", sessionId, "title", title);
        }
        return Map.of("error", "session not found");
    }

    @GetMapping("/system-prompt")
    public Map<String, Object> getSystemPrompt() {
        Path file = Path.of(session.config().dataDir(), "custom-instructions.md");
        Map<String, Object> result = new LinkedHashMap<>();
        if (Files.exists(file)) {
            try {
                result.put("content", Files.readString(file));
            } catch (IOException e) {
                result.put("content", "");
            }
        } else {
            result.put("content", "");
        }
        result.put("path", file.toString());
        result.put("note", "Changes take effect after restart");
        return result;
    }

    @PutMapping("/system-prompt")
    public Map<String, Object> updateSystemPrompt(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null) {
            return Map.of("error", "content is required");
        }
        Path file = Path.of(session.config().dataDir(), "custom-instructions.md");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return Map.of("status", "saved", "note", "Changes take effect after restart");
        } catch (IOException e) {
            return Map.of("error", "Failed to save: " + e.getMessage());
        }
    }

    @GetMapping("/plugins")
    public List<Map<String, Object>> plugins() {
        return session.pluginManager().list().stream()
                .map(inst -> Map.<String, Object>of(
                        "id", inst.id(),
                        "name", inst.metadata().name(),
                        "version", inst.metadata().version(),
                        "description",
                                inst.metadata().description() == null
                                        ? ""
                                        : inst.metadata().description(),
                        "source", inst.source().type(),
                        "scope", inst.scope().name(),
                        "enabled", inst.enabled()))
                .toList();
    }

}
