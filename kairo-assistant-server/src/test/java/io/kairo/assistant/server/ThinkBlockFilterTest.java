package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ThinkBlockFilterTest {

    @Test
    void passesPlainTextThrough() {
        var filter = new ThinkBlockFilter();
        assertEquals("hello world", filter.filter("hello world"));
    }

    @Test
    void stripsSimpleThinkBlock() {
        var filter = new ThinkBlockFilter();
        String result = filter.filter("<think>internal reasoning</think>visible text");
        assertEquals("visible text", result);
    }

    @Test
    void stripsThinkBlockAcrossMultipleChunks() {
        var filter = new ThinkBlockFilter();
        assertEquals("", filter.filter("<think>start"));
        assertEquals("", filter.filter(" of reasoning"));
        String result = filter.filter("</think>now visible");
        assertEquals("now visible", result);
    }

    @Test
    void handlesPartialOpenTagAtChunkBoundary() {
        var filter = new ThinkBlockFilter();
        String r1 = filter.filter("hello <thi");
        String r2 = filter.filter("nk>hidden</think>after");
        assertEquals("hello after", r1 + r2);
    }

    @Test
    void handlesPartialCloseTagAtChunkBoundary() {
        var filter = new ThinkBlockFilter();
        filter.filter("<think>hidden");
        String r1 = filter.filter("</thi");
        String r2 = filter.filter("nk>visible");
        assertEquals("visible", r1 + r2);
    }

    @Test
    void flushReleasesBufferWhenNotInThinkBlock() {
        var filter = new ThinkBlockFilter();
        filter.filter("hello <thi");
        String flushed = filter.flush();
        assertTrue(flushed.contains("<thi"));
    }

    @Test
    void flushDiscardsBufferWhenInThinkBlock() {
        var filter = new ThinkBlockFilter();
        filter.filter("<think>still thinking");
        String flushed = filter.flush();
        assertEquals("", flushed);
    }

    @Test
    void handlesEmptyInput() {
        var filter = new ThinkBlockFilter();
        assertEquals("", filter.filter(""));
        assertEquals("", filter.filter(null));
    }

    @Test
    void handlesMultipleThinkBlocks() {
        var filter = new ThinkBlockFilter();
        String result = filter.filter("A<think>x</think>B<think>y</think>C");
        assertEquals("ABC", result);
    }

    @Test
    void handlesThinkingVariant() {
        var filter = new ThinkBlockFilter();
        String result = filter.filter("<thinking>reasoning</thinking>answer");
        assertEquals("answer", result);
    }

    @Test
    void handlesUppercaseThinkingVariant() {
        var filter = new ThinkBlockFilter();
        String result = filter.filter("<THINKING>deep thought</THINKING>42");
        assertEquals("42", result);
    }

    @Test
    void textBeforeThinkBlockIsPreserved() {
        var filter = new ThinkBlockFilter();
        String result = filter.filter("prefix <think>hidden</think> suffix");
        assertEquals("prefix  suffix", result);
    }

    @Test
    void consecutiveChunksWithNoTags() {
        var filter = new ThinkBlockFilter();
        assertEquals("a", filter.filter("a"));
        assertEquals("b", filter.filter("b"));
        assertEquals("c", filter.filter("c"));
    }
}
