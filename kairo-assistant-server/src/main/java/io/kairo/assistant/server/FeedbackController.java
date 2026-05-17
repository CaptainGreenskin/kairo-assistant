package io.kairo.assistant.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final Path feedbackDir;
    private final EventBroadcaster broadcaster;
    private final CopyOnWriteArrayList<FeedbackEntry> recentFeedback = new CopyOnWriteArrayList<>();

    public FeedbackController(io.kairo.assistant.agent.AssistantSession session, EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
        this.feedbackDir = Path.of(session.config().dataDir(), "feedback");
        try {
            Files.createDirectories(feedbackDir);
        } catch (IOException e) {
            log.warn("Cannot create feedback dir: {}", e.getMessage());
        }
    }

    public record FeedbackEntry(String content, String rating, String feedback, String timestamp) {}

    @PostMapping("/feedback")
    public Map<String, Object> submitFeedback(@RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        String rating = body.getOrDefault("rating", "");
        String fb = body.get("feedback");

        var entry = new FeedbackEntry(content, rating, fb, Instant.now().toString());
        recentFeedback.add(entry);
        if (recentFeedback.size() > 500) {
            recentFeedback.subList(0, recentFeedback.size() - 500).clear();
        }

        persistFeedback(entry);
        broadcaster.broadcast(Map.of("type", "feedback", "rating", rating));

        return Map.of("status", "ok");
    }

    @GetMapping("/feedback")
    public Map<String, Object> listFeedback(@RequestParam(defaultValue = "50") int limit) {
        int size = recentFeedback.size();
        int from = Math.max(0, size - limit);
        List<FeedbackEntry> entries = new ArrayList<>(recentFeedback.subList(from, size));
        long positive = recentFeedback.stream().filter(e -> "positive".equals(e.rating())).count();
        long negative = recentFeedback.stream().filter(e -> "negative".equals(e.rating())).count();
        return Map.of(
                "total", size,
                "positive", positive,
                "negative", negative,
                "entries", entries);
    }

    private void persistFeedback(FeedbackEntry entry) {
        try {
            String line = String.format("%s|%s|%s|%s%n",
                    entry.timestamp(), entry.rating(),
                    entry.content().replace("\n", " ").replace("|", " "),
                    entry.feedback() != null ? entry.feedback().replace("\n", " ").replace("|", " ") : "");
            Files.writeString(feedbackDir.resolve("ratings.log"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Failed to persist feedback: {}", e.getMessage());
        }
    }
}
