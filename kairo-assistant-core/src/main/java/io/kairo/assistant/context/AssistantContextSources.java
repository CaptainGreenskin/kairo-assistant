package io.kairo.assistant.context;

import io.kairo.api.context.ContextSource;
import io.kairo.api.memory.MemoryStore;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AssistantContextSources {

    private static final Logger log = LoggerFactory.getLogger(AssistantContextSources.class);

    private AssistantContextSources() {}

    public static List<ContextSource> defaults(MemoryStore memoryStore) {
        List<ContextSource> sources = new ArrayList<>();
        sources.add(dateTimeSource());
        sources.add(systemInfoSource());
        sources.add(recentMemorySource(memoryStore));
        return sources;
    }

    static ContextSource dateTimeSource() {
        return ContextSource.of("datetime", 1, () -> {
            Instant now = Instant.now();
            ZoneId zone = ZoneId.systemDefault();
            return "Current date/time: "
                    + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                    .withZone(zone).format(now)
                    + " (timezone: " + zone.getId() + ")";
        });
    }

    static ContextSource systemInfoSource() {
        return ContextSource.of("system-info", 5, () -> {
            String os = System.getProperty("os.name", "unknown");
            String user = System.getProperty("user.name", "unknown");
            String home = System.getProperty("user.home", "");
            return "System: " + os + ", user: " + user + ", home: " + home;
        });
    }

    static ContextSource recentMemorySource(MemoryStore memoryStore) {
        return ContextSource.of("recent-memories", 20, () -> {
            try {
                List<String> recent = memoryStore.recent("kairo-assistant", 5)
                        .map(entry -> "- " + entry.content())
                        .collectList()
                        .block(java.time.Duration.ofSeconds(5));
                if (recent == null || recent.isEmpty()) {
                    return "";
                }
                return "Recent memories:\n" + String.join("\n", recent);
            } catch (Exception e) {
                log.warn("Failed to load recent memories for context: {}", e.getMessage());
                return "";
            }
        });
    }
}
