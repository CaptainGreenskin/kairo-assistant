package io.kairo.assistant.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SelfOptimizer {

    private static final Logger log = LoggerFactory.getLogger(SelfOptimizer.class);

    private final Path lessonsFile;
    private final int reviewInterval;
    private final AtomicInteger turnCounter = new AtomicInteger(0);
    private volatile ReviewCallback reviewCallback;

    public SelfOptimizer(Path dataDir, int reviewInterval) {
        this.lessonsFile = dataDir.resolve("lessons_learned.md");
        this.reviewInterval = reviewInterval > 0 ? reviewInterval : 20;
    }

    public void setReviewCallback(ReviewCallback callback) {
        this.reviewCallback = callback;
    }

    public void onTurnComplete(String userMessage, String assistantReply, boolean toolsUsed) {
        int count = turnCounter.incrementAndGet();
        if (count % reviewInterval == 0 && reviewCallback != null) {
            triggerReview(count);
        }
    }

    public void addLesson(String lesson) {
        try {
            Files.createDirectories(lessonsFile.getParent());
            String entry = "\n## " + Instant.now() + "\n" + lesson + "\n";
            Files.writeString(lessonsFile, entry,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Saved lesson: {}", lesson.length() > 60
                    ? lesson.substring(0, 60) + "..." : lesson);
        } catch (IOException e) {
            log.error("Failed to save lesson: {}", e.getMessage());
        }
    }

    public List<String> loadLessons() {
        if (!Files.exists(lessonsFile)) return List.of();
        try {
            String content = Files.readString(lessonsFile);
            List<String> lessons = new ArrayList<>();
            for (String section : content.split("(?=## \\d{4})")) {
                String trimmed = section.trim();
                if (!trimmed.isEmpty()) lessons.add(trimmed);
            }
            return lessons;
        } catch (IOException e) {
            log.error("Failed to load lessons: {}", e.getMessage());
            return List.of();
        }
    }

    public int turnCount() {
        return turnCounter.get();
    }

    private void triggerReview(int turnCount) {
        log.info("Triggering self-review after {} turns", turnCount);
        try {
            reviewCallback.onReview(turnCount);
        } catch (Exception e) {
            log.error("Self-review failed: {}", e.getMessage());
        }
    }

    @FunctionalInterface
    public interface ReviewCallback {
        void onReview(int turnCount);
    }
}
