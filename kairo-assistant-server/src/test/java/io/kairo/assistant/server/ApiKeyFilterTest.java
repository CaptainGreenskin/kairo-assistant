package io.kairo.assistant.server;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyFilterTest {

    @Test
    void allowsRequestWhenNoApiKeyConfigured() throws Exception {
        var filter = new ApiKeyFilter(null);
        var req = new MockHttpServletRequest("GET", "/api/chat");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, resp.getStatus());
    }

    @Test
    void allowsRequestWhenApiKeyIsBlank() throws Exception {
        var filter = new ApiKeyFilter("  ");
        var req = new MockHttpServletRequest("GET", "/api/chat");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void rejectsRequestWithoutKeyWhenKeyConfigured() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/tools");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("Unauthorized"));
        assertNull(chain.getRequest());
    }

    @Test
    void acceptsCorrectBearerToken() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/tools");
        req.addHeader("Authorization", "Bearer secret-key");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, resp.getStatus());
    }

    @Test
    void rejectsWrongBearerToken() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/tools");
        req.addHeader("Authorization", "Bearer wrong-key");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void acceptsApiKeyQueryParam() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/tools");
        req.setParameter("api_key", "secret-key");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void rejectsWrongQueryParam() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/tools");
        req.setParameter("api_key", "wrong");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
    }

    @Test
    void allowsHealthEndpointWithoutKey() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/health");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void allowsDocsEndpointWithoutKey() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/docs");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void allowsOpenApiEndpointWithoutKey() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/openapi.json");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void allowsStaticAssets() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        for (String path : new String[]{"/style.css", "/app.js", "/favicon.ico"}) {
            var req = new MockHttpServletRequest("GET", path);
            var resp = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertNotNull(chain.getRequest(), "Should allow " + path);
        }
    }

    @Test
    void allowsRootPage() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void allowsIndexHtml() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/index.html");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void returnsJsonErrorBody() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/status");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals("application/json", resp.getContentType());
        assertTrue(resp.getContentAsString().contains("\"error\""));
    }

    @Test
    void bearerTokenTakesPrecedenceOverQueryParam() throws Exception {
        var filter = new ApiKeyFilter("secret-key");
        var req = new MockHttpServletRequest("GET", "/api/tools");
        req.addHeader("Authorization", "Bearer secret-key");
        req.setParameter("api_key", "wrong");
        var resp = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertNotNull(chain.getRequest());
    }
}
