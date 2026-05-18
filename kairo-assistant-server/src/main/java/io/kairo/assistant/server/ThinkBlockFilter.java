package io.kairo.assistant.server;

/**
 * Stateful streaming filter that strips {@code <think>...</think>} blocks from token deltas.
 * Each request should create its own instance (not thread-safe across callers).
 */
public class ThinkBlockFilter {

    private static final String[] OPEN_TAGS = {"<think>", "<thinking>", "<THINKING>"};
    private static final String[] CLOSE_TAGS = {"</think>", "</thinking>", "</THINKING>"};

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
                int closePos = indexOfTag(text, i, CLOSE_TAGS);
                if (closePos >= 0) {
                    int tagEnd = endOfTagAt(text, closePos, CLOSE_TAGS);
                    i = tagEnd;
                    inThinkBlock = false;
                } else {
                    String remaining = text.substring(i);
                    if (couldBePartialTag(remaining)) {
                        tailBuffer.setLength(0);
                        tailBuffer.append(remaining);
                        return result.toString();
                    }
                    i = text.length();
                }
            } else {
                int openPos = indexOfTag(text, i, OPEN_TAGS);
                if (openPos >= 0) {
                    result.append(text, i, openPos);
                    int tagEnd = endOfTagAt(text, openPos, OPEN_TAGS);
                    if (tagEnd > openPos) {
                        i = tagEnd;
                        inThinkBlock = true;
                    } else {
                        tailBuffer.setLength(0);
                        tailBuffer.append(text.substring(openPos));
                        return result.toString();
                    }
                } else {
                    int ltIdx = text.indexOf('<', i);
                    if (ltIdx >= 0 && couldBePartialTag(text.substring(ltIdx))) {
                        result.append(text, i, ltIdx);
                        tailBuffer.setLength(0);
                        tailBuffer.append(text.substring(ltIdx));
                        return result.toString();
                    }
                    result.append(text, i, text.length());
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

    private int indexOfTag(String text, int from, String[] tags) {
        int pos = from;
        while (pos < text.length()) {
            int ltIdx = text.indexOf('<', pos);
            if (ltIdx < 0) return -1;
            for (String tag : tags) {
                if (text.regionMatches(true, ltIdx, tag, 0, tag.length())) {
                    return ltIdx;
                }
            }
            pos = ltIdx + 1;
        }
        return -1;
    }

    private int endOfTagAt(String text, int pos, String[] tags) {
        for (String tag : tags) {
            if (text.regionMatches(true, pos, tag, 0, tag.length())) {
                return pos + tag.length();
            }
        }
        return pos;
    }

    private boolean couldBePartialTag(String tail) {
        if (tail.isEmpty()) return false;
        int ltIdx = tail.lastIndexOf('<');
        if (ltIdx < 0) return false;
        String suffix = tail.substring(ltIdx);
        for (String tag : OPEN_TAGS) {
            if (tag.regionMatches(true, 0, suffix, 0, suffix.length()) && suffix.length() < tag.length()) {
                return true;
            }
        }
        for (String tag : CLOSE_TAGS) {
            if (tag.regionMatches(true, 0, suffix, 0, suffix.length()) && suffix.length() < tag.length()) {
                return true;
            }
        }
        return false;
    }
}
