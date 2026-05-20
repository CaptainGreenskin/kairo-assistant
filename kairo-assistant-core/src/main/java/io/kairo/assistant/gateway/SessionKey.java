package io.kairo.assistant.gateway;

import java.util.Objects;

public record SessionKey(String channelId, String destination) {

    public SessionKey {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(destination, "destination");
    }

    public static SessionKey of(String channelId, String destination) {
        return new SessionKey(channelId, destination);
    }

    @Override
    public String toString() {
        return channelId + ":" + destination;
    }
}
