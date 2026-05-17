package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchFilesToolTest {

    private final SearchFilesTool tool = new SearchFilesTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void findsMatchingPattern(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("test.txt"), "Hello Kairo world");
        ToolResult r = tool.execute(
                Map.of("pattern", "Kairo", "path", dir.toString()), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Kairo");
    }

    @Test
    void noMatchesMessage(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("empty.txt"), "nothing here");
        ToolResult r = tool.execute(
                Map.of("pattern", "xyz_nonexistent_pattern", "path", dir.toString()), ctx).block();
        assertThat(r.content()).contains("No matches");
    }

    @Test
    void patternRequired() {
        ToolResult r = tool.execute(Map.of("path", "."), ctx).block();
        assertThat(r.isError()).isTrue();
    }
}
