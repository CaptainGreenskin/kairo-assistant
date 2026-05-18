package io.kairo.assistant.server;

import io.kairo.assistant.gateway.SessionKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SessionAwareDeltaRouter {

    private static final Logger log = LoggerFactory.getLogger(SessionAwareDeltaRouter.class);

    private final ConcurrentHashMap<SessionKey, ConcurrentHashMap<String, Consumer<String>>>
            sessionSubscribers = new ConcurrentHashMap<>();

    public Consumer<String> consumerFor(SessionKey sessionKey) {
        return delta -> {
            var subs = sessionSubscribers.get(sessionKey);
            if (subs != null) {
                for (var entry : subs.entrySet()) {
                    try {
                        entry.getValue().accept(delta);
                    } catch (Exception e) {
                        log.debug("Session [{}] subscriber [{}] threw: {}",
                                sessionKey, entry.getKey(), e.getMessage());
                    }
                }
            }
        };
    }

    public void subscribe(SessionKey sessionKey, String subscriberId, Consumer<String> callback) {
        sessionSubscribers
                .computeIfAbsent(sessionKey, k -> new ConcurrentHashMap<>())
                .put(subscriberId, callback);
    }

    public void unsubscribe(SessionKey sessionKey, String subscriberId) {
        var subs = sessionSubscribers.get(sessionKey);
        if (subs != null) {
            subs.remove(subscriberId);
            if (subs.isEmpty()) {
                sessionSubscribers.remove(sessionKey);
            }
        }
    }

    public void removeSession(SessionKey sessionKey) {
        sessionSubscribers.remove(sessionKey);
    }

    public int sessionCount() {
        return sessionSubscribers.size();
    }
}
