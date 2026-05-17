package io.kairo.assistant.server;

import java.util.Map;

@FunctionalInterface
public interface EventBroadcaster {

    void broadcast(Map<String, Object> event);

    static EventBroadcaster noop() {
        return event -> {};
    }
}
