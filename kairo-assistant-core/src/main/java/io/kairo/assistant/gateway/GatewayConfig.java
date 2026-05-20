package io.kairo.assistant.gateway;

import java.time.Duration;

public record GatewayConfig(int poolMaxSize, Duration idleTtl, float compactionTrigger) {

    public static final int DEFAULT_POOL_MAX_SIZE = 64;
    public static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(60);
    public static final float DEFAULT_COMPACTION_TRIGGER = 0.50f;

    public static GatewayConfig defaults() {
        return new GatewayConfig(DEFAULT_POOL_MAX_SIZE, DEFAULT_IDLE_TTL, DEFAULT_COMPACTION_TRIGGER);
    }

    public static GatewayConfig fromEnv() {
        int poolSize = intEnv("KAIRO_SESSION_POOL_SIZE", DEFAULT_POOL_MAX_SIZE);
        int idleMinutes = intEnv("KAIRO_SESSION_IDLE_TTL_MINUTES", 60);
        float compaction = floatEnv("KAIRO_COMPACTION_TRIGGER", DEFAULT_COMPACTION_TRIGGER);
        return new GatewayConfig(poolSize, Duration.ofMinutes(idleMinutes), compaction);
    }

    private static int intEnv(String name, int defaultValue) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static float floatEnv(String name, float defaultValue) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
