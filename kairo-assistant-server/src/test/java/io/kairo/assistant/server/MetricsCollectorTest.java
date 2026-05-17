package io.kairo.assistant.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCollectorTest {

    @Test
    void recordsRequests() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordRequest();
        metrics.recordRequest();
        metrics.recordRequest();

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_requests_total 3"));
    }

    @Test
    void recordsErrors() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordError();

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_errors_total 1"));
    }

    @Test
    void recordsAgentCalls() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordAgentCall(150);
        metrics.recordAgentCall(250);

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_agent_calls_total 2"));
    }

    @Test
    void recordsAgentErrors() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordAgentError();

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_agent_errors_total 1"));
    }

    @Test
    void recordsStatusCodes() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordStatusCode(200);
        metrics.recordStatusCode(200);
        metrics.recordStatusCode(404);

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("200"));
    }

    @Test
    void recordsToolCalls() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordToolCall("shell");
        metrics.recordToolCall("shell");
        metrics.recordToolCall("calendar");

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("shell"));
    }

    @Test
    void tracksWebSocketConnections() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.wsConnected();
        metrics.wsConnected();
        metrics.wsDisconnected();

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_websocket_active 1"));
    }

    @Test
    void prometheusOutputIsNotEmpty() {
        MetricsCollector metrics = new MetricsCollector();
        String prom = metrics.toPrometheus();

        assertNotNull(prom);
        assertFalse(prom.isEmpty());
        assertTrue(prom.contains("kairo_"));
    }

    @Test
    void recordsTokenUsage() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordTokenUsage(100, 50);
        metrics.recordTokenUsage(200, 80);

        assertEquals(300, metrics.inputTokens());
        assertEquals(130, metrics.outputTokens());
        assertEquals(430, metrics.totalTokens());

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_tokens_input_total 300"));
        assertTrue(prom.contains("kairo_tokens_output_total 130"));
        assertTrue(prom.contains("kairo_tokens_total 430"));
    }

    @Test
    void recordsMessages() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordMessage();
        metrics.recordMessage();
        metrics.recordMessage();

        assertEquals(3, metrics.messageCount());
        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_messages_total 3"));
    }

    @Test
    void tokenUsageStartsAtZero() {
        MetricsCollector metrics = new MetricsCollector();
        assertEquals(0, metrics.inputTokens());
        assertEquals(0, metrics.outputTokens());
        assertEquals(0, metrics.totalTokens());
        assertEquals(0, metrics.messageCount());
    }

    @Test
    void recordsEndpointHits() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordEndpointHit("GET /api/status");
        metrics.recordEndpointHit("GET /api/status");
        metrics.recordEndpointHit("POST /api/summarize");

        assertEquals(2, metrics.endpointHits("GET /api/status"));
        assertEquals(1, metrics.endpointHits("POST /api/summarize"));
        assertEquals(0, metrics.endpointHits("GET /api/unknown"));
    }

    @Test
    void allEndpointHitsReturnsSortedMap() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordEndpointHit("POST /api/summarize");
        metrics.recordEndpointHit("GET /api/tools");
        metrics.recordEndpointHit("GET /api/status");

        var all = metrics.allEndpointHits();
        assertEquals(3, all.size());
        var keys = all.keySet().stream().toList();
        assertEquals("GET /api/status", keys.get(0));
        assertEquals("GET /api/tools", keys.get(1));
        assertEquals("POST /api/summarize", keys.get(2));
    }

    @Test
    void percentileReturnsZeroWhenEmpty() {
        MetricsCollector metrics = new MetricsCollector();
        assertEquals(0, metrics.percentile(50));
        assertEquals(0, metrics.percentile(90));
        assertEquals(0, metrics.percentile(99));
    }

    @Test
    void percentileComputesCorrectly() {
        MetricsCollector metrics = new MetricsCollector();
        for (int i = 1; i <= 100; i++) {
            metrics.recordAgentCall(i * 10);
        }

        assertEquals(500, metrics.percentile(50));
        assertEquals(900, metrics.percentile(90));
        assertEquals(990, metrics.percentile(99));
    }

    @Test
    void durationPercentilesReturnsMap() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordAgentCall(100);
        metrics.recordAgentCall(200);
        metrics.recordAgentCall(300);

        var pcts = metrics.durationPercentiles();
        assertEquals(3, pcts.size());
        assertNotNull(pcts.get("p50"));
        assertNotNull(pcts.get("p90"));
        assertNotNull(pcts.get("p99"));
    }

    @Test
    void percentilesInPrometheusOutput() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordAgentCall(100);
        metrics.recordAgentCall(200);

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_agent_call_duration_ms_p50"));
        assertTrue(prom.contains("kairo_agent_call_duration_ms_p90"));
        assertTrue(prom.contains("kairo_agent_call_duration_ms_p99"));
    }

    @Test
    void endpointHitsInPrometheusOutput() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordEndpointHit("GET /api/status");
        metrics.recordEndpointHit("GET /api/status");

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_endpoint_hits{endpoint=\"GET /api/status\"} 2"));
    }
}
