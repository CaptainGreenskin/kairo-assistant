package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MessageDeliveryTest {

    @Test
    void successOnFirstAttempt() {
        String result = MessageDelivery.sendWithRetry(() -> "ok", "test");
        assertEquals("ok", result);
    }

    @Test
    void retriesOnTransientError() {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = MessageDelivery.sendWithRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("connection reset");
            }
            return "recovered";
        }, "test-retry");
        assertEquals("recovered", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void returnsNullAfterMaxRetries() {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = MessageDelivery.sendWithRetry(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("timeout");
        }, "test-fail");
        assertNull(result);
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryNonRetryableError() {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = MessageDelivery.sendWithRetry(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("HTTP 404 not found");
        }, "test-non-retryable");
        assertNull(result);
        assertEquals(1, attempts.get());
    }

    @Test
    void runnableVersion() {
        AtomicInteger count = new AtomicInteger(0);
        MessageDelivery.sendWithRetry(count::incrementAndGet, "test-runnable");
        assertEquals(1, count.get());
    }

    @Test
    void isRetryableForConnectionErrors() {
        assertTrue(MessageDelivery.isRetryable(new RuntimeException("connection reset")));
        assertTrue(MessageDelivery.isRetryable(new RuntimeException("connect timeout")));
        assertTrue(MessageDelivery.isRetryable(new RuntimeException("broken pipe")));
        assertTrue(MessageDelivery.isRetryable(new RuntimeException("HTTP 502 bad gateway")));
        assertTrue(MessageDelivery.isRetryable(new RuntimeException("HTTP 503 unavailable")));
        assertTrue(MessageDelivery.isRetryable(new RuntimeException("HTTP 429 rate limited")));
    }

    @Test
    void isNotRetryableForClientErrors() {
        assertFalse(MessageDelivery.isRetryable(new RuntimeException("HTTP 400 bad request")));
        assertFalse(MessageDelivery.isRetryable(new RuntimeException("HTTP 401 unauthorized")));
        assertFalse(MessageDelivery.isRetryable(new RuntimeException("HTTP 403 forbidden")));
        assertFalse(MessageDelivery.isRetryable(new RuntimeException("HTTP 404 not found")));
    }

    @Test
    void backoffIncreases() {
        long first = MessageDelivery.backoffDelay(0);
        long second = MessageDelivery.backoffDelay(1);
        assertTrue(first >= 500 && first < 700);
        assertTrue(second >= 1000 && second < 1300);
    }
}
