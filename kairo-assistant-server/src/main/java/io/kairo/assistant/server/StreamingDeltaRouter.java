package io.kairo.assistant.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StreamingDeltaRouter {

    private static final Logger log = LoggerFactory.getLogger(StreamingDeltaRouter.class);

    private final ConcurrentHashMap<String, Consumer<String>> subscriptions = new ConcurrentHashMap<>();

    private final Consumer<String> compositeConsumer = delta -> {
        for (var entry : subscriptions.entrySet()) {
            try {
                entry.getValue().accept(delta);
            } catch (Exception e) {
                log.debug("Delta subscriber [{}] threw: {}", entry.getKey(), e.getMessage());
            }
        }
    };

    public Consumer<String> compositeConsumer() {
        return compositeConsumer;
    }

    public void subscribe(String id, Consumer<String> callback) {
        subscriptions.put(id, callback);
        log.debug("Delta subscriber added: {}", id);
    }

    public void unsubscribe(String id) {
        if (subscriptions.remove(id) != null) {
            log.debug("Delta subscriber removed: {}", id);
        }
    }

    public int subscriberCount() {
        return subscriptions.size();
    }
}
