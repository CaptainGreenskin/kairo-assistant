package io.kairo.assistant.server;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MessageDelivery {

    private static final Logger log = LoggerFactory.getLogger(MessageDelivery.class);
    private static final int MAX_RETRIES = 2;
    private static final long BASE_DELAY_MS = 500;

    private MessageDelivery() {}

    public static <T> T sendWithRetry(Supplier<T> action, String description) {
        Exception lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                lastError = e;
                if (!isRetryable(e) || attempt == MAX_RETRIES) {
                    break;
                }
                long delay = backoffDelay(attempt);
                log.debug("Retry {}/{} for [{}] after {}ms: {}",
                        attempt + 1, MAX_RETRIES, description, delay, e.getMessage());
                sleep(delay);
            }
        }
        log.warn("Message delivery failed after {} attempts for [{}]: {}",
                MAX_RETRIES + 1, description, lastError != null ? lastError.getMessage() : "unknown");
        return null;
    }

    public static void sendWithRetry(Runnable action, String description) {
        sendWithRetry(() -> { action.run(); return Boolean.TRUE; }, description);
    }

    static boolean isRetryable(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return true;
        String lower = msg.toLowerCase();
        if (lower.contains("connect") || lower.contains("timeout")
                || lower.contains("reset") || lower.contains("eof")
                || lower.contains("broken pipe") || lower.contains("503")
                || lower.contains("502") || lower.contains("429")) {
            return true;
        }
        if (lower.contains("400") || lower.contains("401")
                || lower.contains("403") || lower.contains("404")) {
            return false;
        }
        return true;
    }

    static long backoffDelay(int attempt) {
        long delay = BASE_DELAY_MS * (1L << attempt);
        long jitter = ThreadLocalRandom.current().nextLong(delay / 4);
        return delay + jitter;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
