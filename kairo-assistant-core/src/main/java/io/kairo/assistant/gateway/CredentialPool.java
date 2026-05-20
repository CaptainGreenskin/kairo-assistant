package io.kairo.assistant.gateway;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CredentialPool {

    private static final Logger log = LoggerFactory.getLogger(CredentialPool.class);

    private final CopyOnWriteArrayList<Credential> credentials;
    private final AtomicInteger index = new AtomicInteger(0);

    public CredentialPool(List<String> apiKeys) {
        this.credentials = new CopyOnWriteArrayList<>(
                apiKeys.stream().map(Credential::new).toList());
        log.info("Credential pool initialized with {} keys", credentials.size());
    }

    public String next() {
        if (credentials.isEmpty()) {
            throw new IllegalStateException("No credentials available");
        }

        int attempts = credentials.size();
        for (int i = 0; i < attempts; i++) {
            int idx = Math.floorMod(index.getAndIncrement(), credentials.size());
            Credential cred = credentials.get(idx);
            if (!cred.isRateLimited()) {
                return cred.key;
            }
        }

        Credential oldest = credentials.stream()
                .filter(Credential::isRateLimited)
                .min((a, b) -> a.rateLimitedUntil.compareTo(b.rateLimitedUntil))
                .orElse(credentials.get(0));
        log.warn("All keys rate-limited, using least-restricted key");
        return oldest.key;
    }

    public void markRateLimited(String key, int retryAfterSeconds) {
        for (Credential cred : credentials) {
            if (cred.key.equals(key)) {
                cred.rateLimitedUntil = Instant.now().plusSeconds(retryAfterSeconds);
                log.info("Key ...{} rate-limited for {}s",
                        key.substring(Math.max(0, key.length() - 4)), retryAfterSeconds);
                break;
            }
        }
    }

    public void markFailed(String key) {
        for (Credential cred : credentials) {
            if (cred.key.equals(key)) {
                cred.failCount++;
                if (cred.failCount >= 3) {
                    cred.rateLimitedUntil = Instant.now().plusSeconds(300);
                    log.warn("Key ...{} disabled for 5min after {} failures",
                            key.substring(Math.max(0, key.length() - 4)), cred.failCount);
                }
                break;
            }
        }
    }

    public int size() {
        return credentials.size();
    }

    public int availableCount() {
        return (int) credentials.stream().filter(c -> !c.isRateLimited()).count();
    }

    private static class Credential {
        final String key;
        volatile Instant rateLimitedUntil = Instant.EPOCH;
        volatile int failCount = 0;

        Credential(String key) {
            this.key = key;
        }

        boolean isRateLimited() {
            return Instant.now().isBefore(rateLimitedUntil);
        }
    }
}
