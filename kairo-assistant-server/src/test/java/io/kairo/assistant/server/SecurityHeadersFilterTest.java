package io.kairo.assistant.server;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void setsSecurityHeaders() throws Exception {
        var req = new MockHttpServletRequest();
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals("nosniff", resp.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", resp.getHeader("X-Frame-Options"));
        assertEquals("1; mode=block", resp.getHeader("X-XSS-Protection"));
        assertEquals("strict-origin-when-cross-origin", resp.getHeader("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=()", resp.getHeader("Permissions-Policy"));
    }

    @Test
    void setsContentSecurityPolicy() throws Exception {
        var req = new MockHttpServletRequest();
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        String csp = resp.getHeader("Content-Security-Policy");
        assertNotNull(csp);
        assertTrue(csp.contains("default-src 'self'"));
        assertTrue(csp.contains("script-src"));
        assertTrue(csp.contains("connect-src 'self' ws: wss:"));
    }

    @Test
    void chainContinues() throws Exception {
        var req = new MockHttpServletRequest();
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void allSecurityHeadersPresent() throws Exception {
        var req = new MockHttpServletRequest();
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        String[] expectedHeaders = {
                "X-Content-Type-Options", "X-Frame-Options", "X-XSS-Protection",
                "Referrer-Policy", "Permissions-Policy", "Content-Security-Policy"
        };
        for (String header : expectedHeaders) {
            assertNotNull(resp.getHeader(header), "Missing header: " + header);
        }
    }

    @Test
    void cspAllowsWebSocketConnect() throws Exception {
        var req = new MockHttpServletRequest();
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());

        String csp = resp.getHeader("Content-Security-Policy");
        assertTrue(csp.contains("ws:") || csp.contains("wss:"));
    }

    @Test
    void responseStatusUnchanged() throws Exception {
        var req = new MockHttpServletRequest();
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());

        assertEquals(200, resp.getStatus());
    }
}
