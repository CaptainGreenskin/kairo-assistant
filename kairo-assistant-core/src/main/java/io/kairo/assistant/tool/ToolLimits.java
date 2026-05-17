package io.kairo.assistant.tool;

public final class ToolLimits {

    private ToolLimits() {}

    public static final int MAX_OUTPUT_CHARS = 50_000;

    public static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated, total " + text.length() + " chars)";
    }
}
