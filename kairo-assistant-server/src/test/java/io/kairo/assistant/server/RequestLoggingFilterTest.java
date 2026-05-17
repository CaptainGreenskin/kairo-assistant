package io.kairo.assistant.server;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class RequestLoggingFilterTest {

    private final MetricsCollector metrics = new MetricsCollector();
    private final RequestLoggingFilter filter = new RequestLoggingFilter(metrics);

    @Test
    void recordsRequestAndStatusCode() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/chat");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_requests_total 1"));
    }

    @Test
    void skipsHealthEndpoint() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/health");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_requests_total 0"));
    }

    @Test
    void skipsMetricsEndpoint() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/metrics");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_requests_total 0"));
    }

    @Test
    void skipsStaticResources() throws Exception {
        for (String path : new String[]{"/main.css", "/bundle.js", "/favicon.ico"}) {
            var m = new MetricsCollector();
            var f = new RequestLoggingFilter(m);
            var req = new MockHttpServletRequest("GET", path);
            var resp = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            f.doFilter(req, resp, chain);

            assertTrue(m.toPrometheus().contains("kairo_requests_total 0"),
                    "Should skip " + path);
        }
    }

    @Test
    void recordsErrorStatusCodes() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/badpath");
        var resp = new MockHttpServletResponse();
        resp.setStatus(404);
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        String prom = metrics.toPrometheus();
        assertTrue(prom.contains("kairo_errors_total 1"));
    }

    @Test
    void resolveClientIpUsesForwardedHeader() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/chat");
        req.addHeader("X-Forwarded-For", "10.0.0.5, 192.168.1.1");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void recordsEndpointHit() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/tools");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(1, metrics.endpointHits("GET /api/tools"));
    }

    @Test
    void multipleRequestsAccumulateEndpointHits() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/status"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }
        filter.doFilter(
                new MockHttpServletRequest("POST", "/api/chat"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertEquals(3, metrics.endpointHits("GET /api/status"));
        assertEquals(1, metrics.endpointHits("POST /api/chat"));
    }

    @Test
    void skippedPathsDoNotRecordEndpointHit() throws Exception {
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/health"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertEquals(0, metrics.endpointHits("GET /api/health"));
    }

    @Test
    void chainAlwaysContinues() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/status");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }
}
