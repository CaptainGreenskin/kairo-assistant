package io.kairo.assistant.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolInvocation;
import io.kairo.api.tool.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ToolCallLoggerTest {

    private ToolCallLogger logger;

    @BeforeEach
    void setUp() {
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public Mono<ToolResult> execute(String name, Map<String, Object> input) {
                if ("fail_tool".equals(name)) {
                    return Mono.just(ToolResult.error("err-1", "tool failed"));
                }
                return Mono.just(ToolResult.success("ok-1", "result for " + name));
            }
            @Override
            public Mono<ToolResult> execute(String name, Map<String, Object> input, Duration t) {
                return execute(name, input);
            }
            @Override
            public Flux<ToolResult> executeParallel(List<ToolInvocation> inv) {
                return Flux.fromIterable(inv)
                        .flatMap(i -> execute(i.toolName(), i.input()));
            }
        };
        logger = new ToolCallLogger(delegate, 10);
    }

    @Test
    void recordsSuccessfulCall() {
        logger.execute("test_tool", Map.of()).block();

        assertEquals(1, logger.totalCalls());
        assertEquals(0, logger.totalErrors());
        var recent = logger.recentCalls(5);
        assertEquals(1, recent.size());
        assertEquals("test_tool", recent.get(0).toolName());
        assertTrue(recent.get(0).success());
        assertNull(recent.get(0).error());
    }

    @Test
    void recordsErrorCall() {
        logger.execute("fail_tool", Map.of()).block();

        assertEquals(1, logger.totalCalls());
        assertEquals(1, logger.totalErrors());
        var recent = logger.recentCalls(5);
        assertEquals(1, recent.size());
        assertFalse(recent.get(0).success());
    }

    @Test
    void recordsMultipleCalls() {
        logger.execute("tool_a", Map.of()).block();
        logger.execute("tool_b", Map.of()).block();
        logger.execute("tool_a", Map.of()).block();

        assertEquals(3, logger.totalCalls());
        assertEquals(3, logger.recentCalls(10).size());
    }

    @Test
    void recentCallsRespectsLimit() {
        for (int i = 0; i < 8; i++) {
            logger.execute("tool_" + i, Map.of()).block();
        }
        var recent = logger.recentCalls(3);
        assertEquals(3, recent.size());
    }

    @Test
    void ringBufferDropsOldEntries() {
        for (int i = 0; i < 15; i++) {
            logger.execute("tool_" + i, Map.of()).block();
        }
        assertEquals(15, logger.totalCalls());
        assertEquals(10, logger.allCalls().size());
    }

    @Test
    void averageDuration() {
        logger.execute("tool_a", Map.of()).block();
        logger.execute("tool_b", Map.of()).block();

        assertTrue(logger.averageDurationMs() >= 0);
        assertTrue(logger.totalDurationMs() >= 0);
    }

    @Test
    void executeWithTimeout() {
        logger.execute("tool_a", Map.of(), Duration.ofSeconds(5)).block();
        assertEquals(1, logger.totalCalls());
    }

    @Test
    void delegateAccessible() {
        assertNotNull(logger.delegate());
    }

    @Test
    void emptyLoggerHasZeroStats() {
        assertEquals(0, logger.totalCalls());
        assertEquals(0, logger.totalErrors());
        assertEquals(0, logger.averageDurationMs());
        assertTrue(logger.allCalls().isEmpty());
    }

    @Test
    void recentCallsReturnsUnmodifiableList() {
        logger.execute("tool_a", Map.of()).block();
        var recent = logger.recentCalls(5);
        assertThrows(UnsupportedOperationException.class, () -> recent.add(null));
    }

    @Test
    void listenerNotifiedOnSuccess() {
        var received = new CopyOnWriteArrayList<ToolCallLogger.ToolCallRecord>();
        logger.addListener(received::add);

        logger.execute("tool_a", Map.of()).block();

        assertEquals(1, received.size());
        assertEquals("tool_a", received.get(0).toolName());
        assertTrue(received.get(0).success());
    }

    @Test
    void listenerNotifiedOnError() {
        var received = new CopyOnWriteArrayList<ToolCallLogger.ToolCallRecord>();
        logger.addListener(received::add);

        logger.execute("fail_tool", Map.of()).block();

        assertEquals(1, received.size());
        assertFalse(received.get(0).success());
        assertNotNull(received.get(0).error());
    }

    @Test
    void removeListenerStopsNotification() {
        var received = new CopyOnWriteArrayList<ToolCallLogger.ToolCallRecord>();
        java.util.function.Consumer<ToolCallLogger.ToolCallRecord> listener = received::add;
        logger.addListener(listener);
        logger.execute("tool_a", Map.of()).block();
        assertEquals(1, received.size());

        logger.removeListener(listener);
        logger.execute("tool_b", Map.of()).block();
        assertEquals(1, received.size());
    }

    @Test
    void multipleListenersAllNotified() {
        var first = new CopyOnWriteArrayList<ToolCallLogger.ToolCallRecord>();
        var second = new CopyOnWriteArrayList<ToolCallLogger.ToolCallRecord>();
        logger.addListener(first::add);
        logger.addListener(second::add);

        logger.execute("tool_a", Map.of()).block();

        assertEquals(1, first.size());
        assertEquals(1, second.size());
    }

    @Test
    void listenerExceptionDoesNotBreakOthers() {
        var received = new CopyOnWriteArrayList<ToolCallLogger.ToolCallRecord>();
        logger.addListener(r -> { throw new RuntimeException("boom"); });
        logger.addListener(received::add);

        logger.execute("tool_a", Map.of()).block();

        assertEquals(1, received.size());
    }
}
