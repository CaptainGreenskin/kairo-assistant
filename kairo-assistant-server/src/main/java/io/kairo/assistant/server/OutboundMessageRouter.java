package io.kairo.assistant.server;

import io.kairo.assistant.tool.SendMessageTool;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OutboundMessageRouter implements SendMessageTool.OutboundRouter {

    private static final Logger log = LoggerFactory.getLogger(OutboundMessageRouter.class);

    private final ConcurrentHashMap<String, BiFunction<String, String, Boolean>> senders =
            new ConcurrentHashMap<>();

    public void register(String platform, BiFunction<String, String, Boolean> sender) {
        senders.put(platform, sender);
        log.info("Registered outbound sender for platform: {}", platform);
    }

    @Override
    public boolean send(String platform, String destination, String message) {
        BiFunction<String, String, Boolean> sender = senders.get(platform);
        if (sender == null) {
            log.warn("No sender registered for platform: {}", platform);
            return false;
        }
        try {
            return sender.apply(destination, message);
        } catch (Exception e) {
            log.error("Failed to send outbound message to {}/{}: {}",
                    platform, destination, e.getMessage());
            return false;
        }
    }

    public Map<String, BiFunction<String, String, Boolean>> registeredPlatforms() {
        return Map.copyOf(senders);
    }

    public boolean hasPlatform(String platform) {
        return senders.containsKey(platform);
    }

    @Override
    public Set<String> platforms() {
        return Set.copyOf(senders.keySet());
    }
}
