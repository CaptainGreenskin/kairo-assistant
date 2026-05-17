package io.kairo.assistant.tool;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToolLimitsTest {

    @Test
    void maxOutputCharsIsPositive() {
        assertTrue(ToolLimits.MAX_OUTPUT_CHARS > 0);
        assertEquals(50_000, ToolLimits.MAX_OUTPUT_CHARS);
    }

    @Test
    void truncateReturnsShortStringUnchanged() {
        assertEquals("hello", ToolLimits.truncate("hello"));
    }

    @Test
    void truncateHandlesNull() {
        assertEquals("", ToolLimits.truncate(null));
    }

    @Test
    void truncateHandlesEmpty() {
        assertEquals("", ToolLimits.truncate(""));
    }

    @Test
    void truncateHandlesExactLimit() {
        String exact = "a".repeat(ToolLimits.MAX_OUTPUT_CHARS);
        assertEquals(exact, ToolLimits.truncate(exact));
    }

    @Test
    void truncateCutsOverLimit() {
        String over = "a".repeat(ToolLimits.MAX_OUTPUT_CHARS + 100);
        String result = ToolLimits.truncate(over);
        assertTrue(result.startsWith("a".repeat(ToolLimits.MAX_OUTPUT_CHARS)));
        assertTrue(result.contains("truncated"));
        assertTrue(result.contains(String.valueOf(over.length())));
    }
}
