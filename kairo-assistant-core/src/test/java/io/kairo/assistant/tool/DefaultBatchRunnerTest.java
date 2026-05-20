package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultBatchRunnerTest {

    private DefaultBatchRunner runner;

    @BeforeEach
    void setUp() {
        runner = new DefaultBatchRunner(prompt -> "result: " + prompt, 4);
    }

    @AfterEach
    void tearDown() {
        runner.shutdown();
    }

    @Test
    void submitSequentialBatch() throws Exception {
        String id = runner.submit(List.of("a", "b", "c"), false, "session1");
        assertThat(id).hasSize(8);

        Thread.sleep(200);
        String status = runner.status(id);
        assertThat(status).contains("Total: 3");
        assertThat(status).contains("DONE");
    }

    @Test
    void submitParallelBatch() throws Exception {
        String id = runner.submit(List.of("x", "y"), true, "session1");

        Thread.sleep(200);
        String status = runner.status(id);
        assertThat(status).contains("Total: 2");
        assertThat(status).contains("Completed: 2");
    }

    @Test
    void cancelStopsExecution() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        DefaultBatchRunner slow = new DefaultBatchRunner(prompt -> {
            started.countDown();
            try { Thread.sleep(5000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }, 1);

        try {
            String id = slow.submit(List.of("slow1", "slow2", "slow3"), false, "s1");
            started.await(2, TimeUnit.SECONDS);
            boolean cancelled = slow.cancel(id);
            assertThat(cancelled).isTrue();

            Thread.sleep(100);
            assertThat(slow.status(id)).contains("CANCELLED");
        } finally {
            slow.shutdown();
        }
    }

    @Test
    void statusUnknownBatch() {
        assertThat(runner.status("nonexistent")).contains("not found");
    }

    @Test
    void allStatusEmpty() {
        assertThat(runner.allStatus()).isEqualTo("No batches.");
    }

    @Test
    void allStatusShowsBatches() throws Exception {
        runner.submit(List.of("p1"), false, "s1");
        runner.submit(List.of("p2", "p3"), false, "s2");
        Thread.sleep(200);

        String all = runner.allStatus();
        assertThat(all).contains("DONE");
        assertThat(all.lines().count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void handlesExecutionErrors() throws Exception {
        DefaultBatchRunner errRunner = new DefaultBatchRunner(
                prompt -> { throw new RuntimeException("boom"); }, 2);
        try {
            String id = errRunner.submit(List.of("fail1"), false, "s1");
            Thread.sleep(200);
            String status = errRunner.status(id);
            assertThat(status).contains("Failed: 1");
        } finally {
            errRunner.shutdown();
        }
    }

    @Test
    void resultsContainOutput() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        DefaultBatchRunner numbered = new DefaultBatchRunner(
                prompt -> "output-" + counter.incrementAndGet(), 2);
        try {
            String id = numbered.submit(List.of("a", "b"), false, "s1");
            Thread.sleep(200);
            String status = numbered.status(id);
            assertThat(status).contains("output-");
        } finally {
            numbered.shutdown();
        }
    }
}
