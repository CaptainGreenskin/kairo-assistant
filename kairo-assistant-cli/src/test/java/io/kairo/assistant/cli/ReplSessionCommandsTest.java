package io.kairo.assistant.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the single-source-of-truth contract for slash commands: the {@code
 * SLASH_COMMANDS} list (which drives tab-completion) must exactly match the
 * commands dispatched by the {@code switch} in {@code handleSlashCommand}.
 * Adding a command to one but not the other fails here instead of silently
 * shipping a command you can't tab-complete (or can complete but not run).
 */
class ReplSessionCommandsTest {

    private static final Pattern CASE_TOKEN = Pattern.compile("\"(/[a-z]+)\"");

    @Test
    void completerListMatchesDispatchSwitch() throws IOException {
        Set<String> dispatched = parseSwitchCommands();
        Set<String> completed = new LinkedHashSet<>(ReplSession.SLASH_COMMANDS);

        assertTrue(dispatched.size() > 50, "expected to parse the full switch, got " + dispatched);
        assertEquals(dispatched, completed,
                "SLASH_COMMANDS and the handleSlashCommand switch have drifted. "
                        + "Only in switch: " + minus(dispatched, completed)
                        + "; only in SLASH_COMMANDS: " + minus(completed, dispatched));
    }

    private Set<String> parseSwitchCommands() throws IOException {
        Path src = Path.of("src/main/java/io/kairo/assistant/cli/ReplSession.java");
        String body = Files.readString(src);
        int start = body.indexOf("switch (cmd) {");
        int end = body.indexOf("return true;", start);
        String region = body.substring(start, end);

        Set<String> commands = new LinkedHashSet<>();
        for (String line : region.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("case ")) continue;
            Matcher m = CASE_TOKEN.matcher(trimmed);
            while (m.find()) {
                commands.add(m.group(1));
            }
        }
        return commands;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
