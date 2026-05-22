package io.kairo.assistant.server;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class MetricsCollector {

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder totalAgentCalls = new LongAdder();
    private final LongAdder totalAgentErrors = new LongAdder();
    private final AtomicLong activeWebSockets = new AtomicLong();
    private final AtomicLong lastAgentCallDurationMs = new AtomicLong();
    private final LongAdder totalAgentCallDurationMs = new LongAdder();
    private final ConcurrentHashMap<Integer, LongAdder> statusCodeCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> toolCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> endpointCounts = new ConcurrentHashMap<>();
    private final LongAdder totalInputTokens = new LongAdder();
    private final LongAdder totalOutputTokens = new LongAdder();
    private final LongAdder totalMessages = new LongAdder();
    private static final int DURATION_BUFFER_SIZE = 1000;
    private final long[] durationBuffer = new long[DURATION_BUFFER_SIZE];
    private int durationBufferIdx = 0;
    private int durationBufferCount = 0;
    private final ReentrantLock durationLock = new ReentrantLock();

    public void recordRequest() { totalRequests.increment(); }
    public void recordError() { totalErrors.increment(); }
    public void recordAgentCall(long durationMs) {
        totalAgentCalls.increment();
        totalAgentCallDurationMs.add(durationMs);
        lastAgentCallDurationMs.set(durationMs);
        durationLock.lock();
        try {
            durationBuffer[durationBufferIdx] = durationMs;
            durationBufferIdx = (durationBufferIdx + 1) % DURATION_BUFFER_SIZE;
            if (durationBufferCount < DURATION_BUFFER_SIZE) durationBufferCount++;
        } finally {
            durationLock.unlock();
        }
    }
    public void recordAgentError() { totalAgentErrors.increment(); }
    public void recordStatusCode(int code) {
        statusCodeCounts.computeIfAbsent(code, k -> new LongAdder()).increment();
    }
    public void recordToolCall(String toolName) {
        toolCallCounts.computeIfAbsent(toolName, k -> new LongAdder()).increment();
    }
    public void recordTokenUsage(long inputTokens, long outputTokens) {
        totalInputTokens.add(inputTokens);
        totalOutputTokens.add(outputTokens);
    }
    public void recordMessage() { totalMessages.increment(); }
    public void recordEndpointHit(String endpoint) {
        endpointCounts.computeIfAbsent(normalizeEndpoint(endpoint), k -> new LongAdder())
                .increment();
    }

    /**
     * Collapse high-cardinality path segments to placeholders so the endpoint
     * counter map doesn't grow unbounded as session/task/plugin IDs flow
     * through the URL space (F24). Matches: UUID, hex 8-32, plugin id form
     * `path:name:uuid` or `github:hash:uuid`, then individual session-id
     * fragments inside known prefixes.
     */
    static String normalizeEndpoint(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        int sp = raw.indexOf(' ');
        String prefix = sp >= 0 ? raw.substring(0, sp + 1) : "";
        String path = sp >= 0 ? raw.substring(sp + 1) : raw;
        boolean leadingSlash = path.startsWith("/");
        String[] segs = path.split("/");
        StringBuilder out = new StringBuilder();
        if (leadingSlash) out.append('/');
        boolean first = true;
        for (String s : segs) {
            if (s.isEmpty()) continue;
            if (!first) out.append('/');
            first = false;
            if (s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    || s.matches("[0-9a-fA-F]{8,64}")
                    || s.matches("\\d{4,}")
                    || (s.contains(":") && s.split(":").length >= 2)) {
                out.append(":id");
            } else {
                out.append(s);
            }
        }
        return prefix + out.toString();
    }
    public long endpointHits(String endpoint) {
        LongAdder adder = endpointCounts.get(endpoint);
        return adder != null ? adder.sum() : 0;
    }
    public java.util.Map<String, Long> allEndpointHits() {
        var result = new java.util.TreeMap<String, Long>();
        endpointCounts.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }
    public java.util.Map<String, Long> toolCallStats() {
        var result = new java.util.TreeMap<String, Long>();
        toolCallCounts.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }
    public long inputTokens() { return totalInputTokens.sum(); }
    public long outputTokens() { return totalOutputTokens.sum(); }
    public long totalTokens() { return totalInputTokens.sum() + totalOutputTokens.sum(); }
    public long messageCount() { return totalMessages.sum(); }
    public long agentCalls() { return totalAgentCalls.sum(); }
    public long agentDurationTotalMs() { return totalAgentCallDurationMs.sum(); }
    public long percentile(double p) {
        durationLock.lock();
        try {
            if (durationBufferCount == 0) return 0;
            long[] snapshot = new long[durationBufferCount];
            System.arraycopy(durationBuffer, 0, snapshot, 0, durationBufferCount);
            Arrays.sort(snapshot);
            int idx = (int) Math.ceil(p / 100.0 * snapshot.length) - 1;
            return snapshot[Math.max(0, Math.min(idx, snapshot.length - 1))];
        } finally {
            durationLock.unlock();
        }
    }
    public java.util.Map<String, Long> durationPercentiles() {
        var result = new java.util.LinkedHashMap<String, Long>();
        result.put("p50", percentile(50));
        result.put("p90", percentile(90));
        result.put("p99", percentile(99));
        return result;
    }
    public void wsConnected() { activeWebSockets.incrementAndGet(); }
    public void wsDisconnected() { activeWebSockets.decrementAndGet(); }

    public String toPrometheus() {
        StringBuilder sb = new StringBuilder();
        gauge(sb, "kairo_requests_total", "Total HTTP requests", totalRequests.sum());
        gauge(sb, "kairo_errors_total", "Total error responses", totalErrors.sum());
        gauge(sb, "kairo_agent_calls_total", "Total agent call invocations", totalAgentCalls.sum());
        gauge(sb, "kairo_agent_errors_total", "Total agent call errors", totalAgentErrors.sum());
        gauge(sb, "kairo_agent_call_duration_ms_total", "Total agent call duration in ms", totalAgentCallDurationMs.sum());
        gauge(sb, "kairo_agent_call_duration_ms_last", "Last agent call duration in ms", lastAgentCallDurationMs.get());
        gauge(sb, "kairo_websocket_active", "Active WebSocket connections", activeWebSockets.get());
        gauge(sb, "kairo_tokens_input_total", "Total input tokens consumed", totalInputTokens.sum());
        gauge(sb, "kairo_tokens_output_total", "Total output tokens generated", totalOutputTokens.sum());
        gauge(sb, "kairo_tokens_total", "Total tokens (input + output)", totalInputTokens.sum() + totalOutputTokens.sum());
        gauge(sb, "kairo_messages_total", "Total messages processed", totalMessages.sum());

        if (totalAgentCalls.sum() > 0) {
            gauge(sb, "kairo_agent_call_duration_ms_avg",
                    "Average agent call duration in ms",
                    totalAgentCallDurationMs.sum() / totalAgentCalls.sum());
            gauge(sb, "kairo_agent_call_duration_ms_p50", "P50 agent call duration in ms", percentile(50));
            gauge(sb, "kairo_agent_call_duration_ms_p90", "P90 agent call duration in ms", percentile(90));
            gauge(sb, "kairo_agent_call_duration_ms_p99", "P99 agent call duration in ms", percentile(99));
        }

        for (var entry : statusCodeCounts.entrySet()) {
            sb.append(String.format("kairo_http_status{code=\"%d\"} %d%n", entry.getKey(), entry.getValue().sum()));
        }

        for (var entry : toolCallCounts.entrySet()) {
            sb.append(String.format("kairo_tool_calls{tool=\"%s\"} %d%n", entry.getKey(), entry.getValue().sum()));
        }

        for (var entry : endpointCounts.entrySet()) {
            sb.append(String.format("kairo_endpoint_hits{endpoint=\"%s\"} %d%n", entry.getKey(), entry.getValue().sum()));
        }

        return sb.toString();
    }

    private void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }
}
