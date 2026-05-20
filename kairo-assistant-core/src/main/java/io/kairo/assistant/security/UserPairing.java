package io.kairo.assistant.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserPairing {

    private static final Logger log = LoggerFactory.getLogger(UserPairing.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final SecureRandom random = new SecureRandom();

    private final Path storePath;
    private final ConcurrentHashMap<String, PairedUser> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingPairing> pendingCodes = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final boolean enabled;

    public UserPairing(Path dataDir, boolean enabled) {
        this.storePath = dataDir.resolve("paired_users.json");
        this.enabled = enabled;
        load();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAuthorized(String platform, String userId) {
        if (!enabled) return true;
        String key = platform + ":" + userId;
        return users.containsKey(key);
    }

    public String generatePairingCode(String platform, String userId) {
        String code = String.format("%06d", random.nextInt(1000000));
        pendingCodes.put(code, new PendingPairing(platform, userId, Instant.now()));
        log.info("Generated pairing code for {}:{}", platform, userId);
        return code;
    }

    public boolean completePairing(String code) {
        PendingPairing pending = pendingCodes.remove(code);
        if (pending == null) return false;

        if (Instant.now().isAfter(pending.createdAt().plusSeconds(300))) {
            log.warn("Pairing code expired for {}:{}", pending.platform(), pending.userId());
            return false;
        }

        String key = pending.platform() + ":" + pending.userId();
        users.put(key, new PairedUser(pending.platform(), pending.userId(), Instant.now()));
        persist();
        log.info("User paired: {}", key);
        return true;
    }

    public boolean unpair(String platform, String userId) {
        String key = platform + ":" + userId;
        if (users.remove(key) != null) {
            persist();
            log.info("User unpaired: {}", key);
            return true;
        }
        return false;
    }

    public Optional<PairedUser> getUser(String platform, String userId) {
        return Optional.ofNullable(users.get(platform + ":" + userId));
    }

    public Map<String, PairedUser> allUsers() {
        return Map.copyOf(users);
    }

    public void cleanExpiredCodes() {
        Instant cutoff = Instant.now().minusSeconds(300);
        pendingCodes.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    private void load() {
        lock.readLock().lock();
        try {
            if (Files.exists(storePath)) {
                Map<String, PairedUser> loaded = mapper.readValue(
                        storePath.toFile(), new TypeReference<>() {});
                users.putAll(loaded);
                log.info("Loaded {} paired users", users.size());
            }
        } catch (IOException e) {
            log.error("Failed to load paired users: {}", e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }

    private void persist() {
        lock.writeLock().lock();
        try {
            Files.createDirectories(storePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), users);
        } catch (IOException e) {
            log.error("Failed to persist paired users: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public record PairedUser(String platform, String userId, Instant pairedAt) {}

    public record PendingPairing(String platform, String userId, Instant createdAt) {}
}
