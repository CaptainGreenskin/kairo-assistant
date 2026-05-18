package io.kairo.assistant.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TerminalMarkdownRendererTest {

    @Test
    void rendersHeadings() {
        String result = TerminalMarkdownRenderer.render("# Hello World");
        assertNotEquals("# Hello World", result);
        assertTrue(result.contains("Hello World"));
        assertTrue(result.contains("\033[1m"));
    }

    @Test
    void rendersBoldText() {
        String result = TerminalMarkdownRenderer.render("this is **bold** text");
        assertTrue(result.contains("\033[1m"));
        assertTrue(result.contains("bold"));
        assertFalse(result.contains("**"));
    }

    @Test
    void rendersInlineCode() {
        String result = TerminalMarkdownRenderer.render("use `git status` command");
        assertTrue(result.contains("\033[36m"));
        assertTrue(result.contains("git status"));
        assertFalse(result.contains("`"));
    }

    @Test
    void rendersCodeBlocks() {
        String result = TerminalMarkdownRenderer.render("```java\nSystem.out.println();\n```");
        assertTrue(result.contains("System.out.println();"));
        assertTrue(result.contains("[java]"));
    }

    @Test
    void rendersUnorderedList() {
        String result = TerminalMarkdownRenderer.render("- item one\n- item two");
        assertTrue(result.contains("•"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(TerminalMarkdownRenderer.render(null));
        assertEquals("", TerminalMarkdownRenderer.render(""));
    }

    @Test
    void rendersLinks() {
        String result = TerminalMarkdownRenderer.render("see [docs](https://example.com)");
        assertTrue(result.contains("docs"));
        assertTrue(result.contains("https://example.com"));
        assertFalse(result.contains("[docs]"));
    }

    @Test
    void rendersHorizontalRule() {
        String result = TerminalMarkdownRenderer.render("above\n---\nbelow");
        assertTrue(result.contains("────"));
    }

    @Test
    void rendersItalicText() {
        String result = TerminalMarkdownRenderer.render("this is *italic* text");
        assertTrue(result.contains("\033[3m"));
        assertTrue(result.contains("italic"));
        assertFalse(result.contains("*italic*"));
    }

    @Test
    void rendersOrderedList() {
        String result = TerminalMarkdownRenderer.render("1. first\n2. second");
        assertTrue(result.contains("1."));
        assertTrue(result.contains("first"));
    }

    @Test
    void rendersCodeBlockWithoutLanguage() {
        String result = TerminalMarkdownRenderer.render("```\nhello\n```");
        assertTrue(result.contains("hello"));
        assertFalse(result.contains("```"));
    }

    @Test
    void plainTextUnchanged() {
        String result = TerminalMarkdownRenderer.render("plain text with no markdown");
        assertEquals("plain text with no markdown", result);
    }
}
