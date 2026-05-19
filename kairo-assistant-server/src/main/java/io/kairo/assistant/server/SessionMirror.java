package io.kairo.assistant.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.assistant.gateway.SessionKey;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SessionMirror {

    private static final Logger log = LoggerFactory.getLogger(SessionMirror.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConcurrentHashMap<SessionKey, Set<Consumer<String>>> subscribers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Consumer<String>>> userSubscribers =
            new ConcurrentHashMap<>();
    private final Set<Consumer<String>> globalSubscribers = new CopyOnWriteArraySet<>();

    public void subscribe(SessionKey sessionKey, Consumer<String> listener) {
        subscribers.computeIfAbsent(sessionKey, k -> new CopyOnWriteArraySet<>()).add(listener);
    }

    public void unsubscribe(SessionKey sessionKey, Consumer<String> listener) {
        Set<Consumer<String>> subs = subscribers.get(sessionKey);
        if (subs != null) {
            subs.remove(listener);
            if (subs.isEmpty()) subscribers.remove(sessionKey);
        }
    }

    public void subscribeUser(String userId, Consumer<String> listener) {
        userSubscribers.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(listener);
    }

    public void unsubscribeUser(String userId, Consumer<String> listener) {
        Set<Consumer<String>> subs = userSubscribers.get(userId);
        if (subs != null) {
            subs.remove(listener);
            if (subs.isEmpty()) userSubscribers.remove(userId);
        }
    }

    public void subscribeAll(Consumer<String> listener) {
        globalSubscribers.add(listener);
    }

    public void unsubscribeAll(Consumer<String> listener) {
        globalSubscribers.remove(listener);
    }

    public void onMessage(SessionKey sessionKey, String direction, String platform,
                          String content) {
        MirrorEvent event = new MirrorEvent(
                sessionKey.channelId(), sessionKey.destination(),
                direction, platform, content, Instant.now().toString());

        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Failed to serialize mirror event", e);
            return;
        }

        Set<Consumer<String>> sessionSubs = subscribers.get(sessionKey);
        if (sessionSubs != null) {
            for (Consumer<String> sub : sessionSubs) {
                safeSend(sub, json);
            }
        }

        Set<Consumer<String>> userSubs = userSubscribers.get(sessionKey.destination());
        if (userSubs != null) {
            for (Consumer<String> sub : userSubs) {
                safeSend(sub, json);
            }
        }

        for (Consumer<String> sub : globalSubscribers) {
            safeSend(sub, json);
        }
    }

    public void onInbound(SessionKey sessionKey, String platform, String message) {
        onMessage(sessionKey, "inbound", platform, message);
    }

    public void onOutbound(SessionKey sessionKey, String platform, String message) {
        onMessage(sessionKey, "outbound", platform, message);
    }

    public int subscriberCount() {
        return subscribers.values().stream().mapToInt(Set::size).sum()
                + userSubscribers.values().stream().mapToInt(Set::size).sum()
                + globalSubscribers.size();
    }

    public Map<String, Integer> stats() {
        return Map.of(
                "sessionSubscriptions", subscribers.size(),
                "userSubscriptions", userSubscribers.size(),
                "totalListeners", subscriberCount());
    }

    private void safeSend(Consumer<String> consumer, String json) {
        try {
            consumer.accept(json);
        } catch (Exception e) {
            log.debug("Mirror subscriber threw: {}", e.getMessage());
        }
    }

    public record MirrorEvent(String sessionPlatform, String sessionUser,
                              String direction, String sourcePlatform,
                              String content, String timestamp) {}
}
