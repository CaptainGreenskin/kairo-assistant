package io.kairo.assistant.server;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter();

    @Test
    void tryAcquireAllowsWithinLimit() throws Exception {
        Method tryAcquire = RateLimitFilter.class.getDeclaredMethod("tryAcquire", String.class);
        tryAcquire.setAccessible(true);

        for (int i = 0; i < 60; i++) {
            assertTrue((Boolean) tryAcquire.invoke(filter, "testClient"));
        }
    }

    @Test
    void tryAcquireRejectsOverLimit() throws Exception {
        Method tryAcquire = RateLimitFilter.class.getDeclaredMethod("tryAcquire", String.class);
        tryAcquire.setAccessible(true);

        for (int i = 0; i < 60; i++) {
            tryAcquire.invoke(filter, "overLimitClient");
        }
        assertFalse((Boolean) tryAcquire.invoke(filter, "overLimitClient"));
    }

    @Test
    void remainingDecreasesWithRequests() throws Exception {
        Method tryAcquire = RateLimitFilter.class.getDeclaredMethod("tryAcquire", String.class);
        tryAcquire.setAccessible(true);
        Method remaining = RateLimitFilter.class.getDeclaredMethod("remaining", String.class);
        remaining.setAccessible(true);

        assertEquals(60, (Integer) remaining.invoke(filter, "newClient"));

        tryAcquire.invoke(filter, "newClient");
        tryAcquire.invoke(filter, "newClient");

        int rem = (Integer) remaining.invoke(filter, "newClient");
        assertEquals(58, rem);
    }

    @Test
    void differentClientsHaveIndependentLimits() throws Exception {
        Method tryAcquire = RateLimitFilter.class.getDeclaredMethod("tryAcquire", String.class);
        tryAcquire.setAccessible(true);

        for (int i = 0; i < 60; i++) {
            tryAcquire.invoke(filter, "client1");
        }
        assertFalse((Boolean) tryAcquire.invoke(filter, "client1"));
        assertTrue((Boolean) tryAcquire.invoke(filter, "client2"));
    }

    @Test
    void resolveClientKeyUsesForwardedHeader() throws Exception {
        Method resolve = RateLimitFilter.class.getDeclaredMethod("resolveClientKey",
                jakarta.servlet.http.HttpServletRequest.class);
        resolve.setAccessible(true);

        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");

        String key = (String) resolve.invoke(filter, req);
        assertEquals("10.0.0.1", key);
    }

    @Test
    void resolveClientKeyFallsBackToRemoteAddr() throws Exception {
        Method resolve = RateLimitFilter.class.getDeclaredMethod("resolveClientKey",
                jakarta.servlet.http.HttpServletRequest.class);
        resolve.setAccessible(true);

        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setRemoteAddr("192.168.0.5");

        String key = (String) resolve.invoke(filter, req);
        assertEquals("192.168.0.5", key);
    }

    @Test
    void initialRemainingIs60() throws Exception {
        Method remaining = RateLimitFilter.class.getDeclaredMethod("remaining", String.class);
        remaining.setAccessible(true);

        assertEquals(60, (Integer) remaining.invoke(filter, "brandNewClient"));
    }

    @Test
    void resolveClientKeyWithBlankForwardedUsesRemoteAddr() throws Exception {
        Method resolve = RateLimitFilter.class.getDeclaredMethod("resolveClientKey",
                jakarta.servlet.http.HttpServletRequest.class);
        resolve.setAccessible(true);

        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.99");
        req.addHeader("X-Forwarded-For", "");

        String key = (String) resolve.invoke(filter, req);
        assertEquals("10.0.0.99", key);
    }
}
