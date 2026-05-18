package io.kairo.assistant.server;

/**
 * Stateful streaming filter that strips {@code <think>...</think>} blocks from token deltas.
 * Each request should create its own instance (not thread-safe across callers).
 */
public class ThinkBlockFilter {

    private static final String[] OPEN_TAGS = {"<think>", "<thinking>", "<THINKING>"};
    private static final String[] CLOSE_TAGS = {"</think>", "</thinking>", "</THINKING>"};
    private static final int MAX_TAG_LENGTH = "</THINKING>".length();

    private boolean inThinkBlock = false;
    private final StringBuilder tailBuffer = new StringBuilder();

    public String filter(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }

        tailBuffer.append(delta);
        String text = tailBuffer.toString();
        StringBuilder result = new StringBuilder();

        int i = 0;
        while (i < text.length()) {
            if (inThinkBlock) {
                int closeIdx = findTag(text, i, CLOSE_TAGS);
                if (closeIdx >= 0) {
                    i = closeIdx;
                    inThinkBlock = false;
                } else {
                    // Could be a partial close tag at the end — hold the tail
                    String remaining = text.substring(i);
                    if (couldBePartialTag(remaining, CLOSE_TAGS)) {
                        tailBuffer.setLength(0);
                        tailBuffer.append(remaining);
                        return result.toString();
                    }
                    i = text.length();
                }
            } else {
                int openIdx = findTagStart(text, i, OPEN_TAGS);
                if (openIdx >= 0) {
                    result.append(text, i, openIdx);
                    int tagEnd = findTag(text, openIdx, OPEN_TAGS);
                    if (tagEnd >= 0) {
                        i = tagEnd;
                        inThinkBlock = true;
                    } else {
                        // Partial open tag at end — hold it
                        tailBuffer.setLength(0);
                        tailBuffer.append(text.substring(openIdx));
                        return result.toString();
                    }
                } else {
                    // No tag found — but tail might be start of a tag
                    int safeEnd = safeCopyEnd(text, i, OPEN_TAGS);
                    result.append(text, i, safeEnd);
                    if (safeEnd < text.length()) {
                        tailBuffer.setLength(0);
                        tailBuffer.append(text.substring(safeEnd));
                        return result.toString();
                    }
                    i = text.length();
                }
            }
        }

        tailBuffer.setLength(0);
        return result.toString();
    }

    public String flush() {
        if (tailBuffer.isEmpty()) {
            return "";
        }
        String remaining = tailBuffer.toString();
        tailBuffer.setLength(0);
        return inThinkBlock ? "" : remaining;
    }

    private int findTagStart(String text, int from, String[] tags) {
        int earliest = -1;
        for (String tag : tags) {
            int idx = text.indexOf(tag.charAt(0) == '<' ? "<" : tag, from);
            if (idx >= 0 && text.regionMatches(true, idx, tag, 0, Math.min(tag.length(), text.length() - idx))) {
                if (earliest < 0 || idx < earliest) {
                    earliest = idx;
                }
            }
        }
        // Also check for '<' that could be start of any tag
        int ltIdx = text.indexOf('<', from);
        if (ltIdx >= 0 && (earliest < 0 || ltIdx < earliest)) {
            for (String tag : tags) {
                if (text.regionMatches(true, ltIdx, tag, 0, Math.min(tag.length(), text.length() - ltIdx))) {
                    return ltIdx;
                }
            }
        }
        return earliest;
    }

    private int findTag(String text, int from, String[] tags) {
        for (String tag : tags) {
            if (text.regionMatches(true, from, tag, 0, tag.length())) {
                return from + tag.length();
            }
        }
        return -1;
    }

    private boolean couldBePartialTag(String text, String[] tags) {
        if (text.isEmpty() || text.charAt(0) != '<') {
            return false;
        }
        for (String tag : tags) {
            if (tag.regionMatches(true, 0, text, 0, text.length())) {
                return true;
            }
        }
        return false;
    }

    private int safeCopyEnd(String text, int from, String[] tags) {
        int ltIdx = text.indexOf('<', from);
        if (ltIdx < 0) {
            return text.length();
        }
        String tail = text.substring(ltIdx);
        for (String tag : tags) {
            if (tag.regionMatches(true, 0, tail, 0, Math.min(tag.length(), tail.length()))) {
                return ltIdx;
            }
        }
        return text.length();
    }
}
