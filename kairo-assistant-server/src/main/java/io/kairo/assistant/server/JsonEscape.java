package io.kairo.assistant.server;

final class JsonEscape {

    private JsonEscape() {}

    static String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
