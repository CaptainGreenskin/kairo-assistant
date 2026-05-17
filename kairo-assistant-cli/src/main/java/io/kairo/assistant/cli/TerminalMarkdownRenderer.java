package io.kairo.assistant.cli;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerminalMarkdownRenderer {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String ITALIC = "\033[3m";
    private static final String UNDERLINE = "\033[4m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String MAGENTA = "\033[35m";
    private static final String GRAY = "\033[90m";

    private static final Pattern CODE_BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*([^*]+?)\\*(?!\\*)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern UNORDERED_LIST = Pattern.compile("^(\\s*)[-*]\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\s*)\\d+\\.\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^---+$", Pattern.MULTILINE);

    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown;

        String result = markdown;

        result = renderCodeBlocks(result);
        result = renderHeadings(result);
        result = renderHorizontalRules(result);
        result = renderBold(result);
        result = renderItalic(result);
        result = renderInlineCode(result);
        result = renderLinks(result);
        result = renderUnorderedLists(result);
        result = renderOrderedLists(result);

        return result;
    }

    private static String renderCodeBlocks(String text) {
        Matcher m = CODE_BLOCK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String lang = m.group(1);
            String code = m.group(2);
            String header = lang.isEmpty() ? "" : GRAY + "  [" + lang + "]" + RESET + "\n";
            String rendered = header + formatCodeBlock(code);
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String formatCodeBlock(String code) {
        StringBuilder sb = new StringBuilder();
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) continue;
            sb.append(DIM).append("  │ ").append(RESET)
              .append(CYAN).append(lines[i]).append(RESET);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private static String renderHeadings(String text) {
        Matcher m = HEADING.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int level = m.group(1).length();
            String content = m.group(2);
            String rendered = switch (level) {
                case 1 -> BOLD + UNDERLINE + content + RESET;
                case 2 -> BOLD + content + RESET;
                default -> BOLD + DIM + content + RESET;
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String renderBold(String text) {
        Matcher m = BOLD_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(BOLD + m.group(1) + RESET));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String renderItalic(String text) {
        Matcher m = ITALIC_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(ITALIC + m.group(1) + RESET));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String renderInlineCode(String text) {
        Matcher m = INLINE_CODE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(CYAN + m.group(1) + RESET));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String renderLinks(String text) {
        Matcher m = LINK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String label = m.group(1);
            String url = m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    UNDERLINE + label + RESET + " " + GRAY + "(" + url + ")" + RESET));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String renderUnorderedLists(String text) {
        Matcher m = UNORDERED_LIST.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String indent = m.group(1);
            String content = m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(indent + GREEN + "  • " + RESET + content));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String renderOrderedLists(String text) {
        Matcher m = ORDERED_LIST.matcher(text);
        StringBuilder sb = new StringBuilder();
        int counter = 1;
        while (m.find()) {
            String indent = m.group(1);
            String content = m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    indent + GREEN + "  " + counter + ". " + RESET + content));
            counter++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String renderHorizontalRules(String text) {
        Matcher m = HORIZONTAL_RULE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(DIM + "  ────────────────────────────────" + RESET));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
