package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolLimitsTest {

    @Test
    void maxOutputCharsIsPositive() {
        assertThat(ToolLimits.MAX_OUTPUT_CHARS).isPositive();
        assertThat(ToolLimits.MAX_OUTPUT_CHARS).isEqualTo(50_000);
    }

    @Test
    void truncateReturnsShortStringUnchanged() {
        assertThat(ToolLimits.truncate("hello")).isEqualTo("hello");
    }

    @Test
    void truncateHandlesNull() {
        assertThat(ToolLimits.truncate(null)).isEmpty();
    }

    @Test
    void truncateHandlesEmpty() {
        assertThat(ToolLimits.truncate("")).isEmpty();
    }

    @Test
    void truncateHandlesExactLimit() {
        String exact = "a".repeat(ToolLimits.MAX_OUTPUT_CHARS);
        assertThat(ToolLimits.truncate(exact)).isEqualTo(exact);
    }

    @Test
    void truncateCutsOverLimit() {
        String over = "a".repeat(ToolLimits.MAX_OUTPUT_CHARS + 100);
        String result = ToolLimits.truncate(over);
        assertThat(result).startsWith("a".repeat(100));
        assertThat(result).contains("truncated");
        assertThat(result).contains(String.valueOf(over.length()));
    }

    @Test
    void truncateOneBeyondLimit() {
        String over = "x".repeat(ToolLimits.MAX_OUTPUT_CHARS + 1);
        String result = ToolLimits.truncate(over);
        assertThat(result).hasSize(ToolLimits.MAX_OUTPUT_CHARS +
                ("\n... (truncated, total " + over.length() + " chars)").length());
    }

    @Test
    void truncatePreservesPrefix() {
        String prefix = "PREFIX_";
        String over = prefix + "y".repeat(ToolLimits.MAX_OUTPUT_CHARS);
        String result = ToolLimits.truncate(over);
        assertThat(result).startsWith(prefix);
    }

    @Test
    void truncateSuffixContainsTotalLength() {
        int total = ToolLimits.MAX_OUTPUT_CHARS + 5000;
        String over = "z".repeat(total);
        String result = ToolLimits.truncate(over);
        assertThat(result).contains(String.valueOf(total));
    }

    @Test
    void truncateSingleCharString() {
        assertThat(ToolLimits.truncate("a")).isEqualTo("a");
    }

    @Test
    void truncateWhitespaceOnly() {
        assertThat(ToolLimits.truncate("   ")).isEqualTo("   ");
    }
}
