package io.kairo.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfOptimizerTest {

    @TempDir
    Path tempDir;

    @Test
    void triggersReviewAtInterval() {
        AtomicInteger reviewCount = new AtomicInteger();
        SelfOptimizer optimizer = new SelfOptimizer(tempDir, 5);
        optimizer.setReviewCallback(turnCount -> reviewCount.incrementAndGet());

        for (int i = 0; i < 5; i++) {
            optimizer.onTurnComplete("user", "assistant", false);
        }

        assertThat(reviewCount.get()).isEqualTo(1);
    }

    @Test
    void doesNotTriggerBeforeInterval() {
        AtomicInteger reviewCount = new AtomicInteger();
        SelfOptimizer optimizer = new SelfOptimizer(tempDir, 10);
        optimizer.setReviewCallback(turnCount -> reviewCount.incrementAndGet());

        for (int i = 0; i < 9; i++) {
            optimizer.onTurnComplete("u", "a", false);
        }

        assertThat(reviewCount.get()).isEqualTo(0);
    }

    @Test
    void addAndLoadLessons() {
        SelfOptimizer optimizer = new SelfOptimizer(tempDir, 20);
        optimizer.addLesson("Always validate user input");
        optimizer.addLesson("Use streaming for large responses");

        assertThat(optimizer.loadLessons()).hasSize(2);
    }

    @Test
    void emptyLessonsReturnsEmptyList() {
        SelfOptimizer optimizer = new SelfOptimizer(tempDir, 20);
        assertThat(optimizer.loadLessons()).isEmpty();
    }

    @Test
    void turnCounterIncrements() {
        SelfOptimizer optimizer = new SelfOptimizer(tempDir, 100);
        optimizer.onTurnComplete("a", "b", true);
        optimizer.onTurnComplete("a", "b", true);
        assertThat(optimizer.turnCount()).isEqualTo(2);
    }
}
