package io.kairo.assistant.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SoulLoader {

    private static final Logger log = LoggerFactory.getLogger(SoulLoader.class);
    private static final String DEFAULT_FILE = "SOUL.md";

    private SoulLoader() {}

    public static String load(Path dataDir) {
        Path soulFile = dataDir.resolve(DEFAULT_FILE);
        if (!Files.exists(soulFile)) {
            log.debug("No SOUL.md found at {}", soulFile);
            return null;
        }
        try {
            String content = Files.readString(soulFile).trim();
            if (content.isBlank()) return null;
            log.info("Loaded personality from SOUL.md ({} chars)", content.length());
            return content;
        } catch (IOException e) {
            log.warn("Failed to read SOUL.md: {}", e.getMessage());
            return null;
        }
    }
}
