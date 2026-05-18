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

    @Test
    void blankPatternErrors() {
        ToolResult r = tool.execute(Map.of("pattern", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void includeFilterNarrowsSearch(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("code.java"), "public class Foo {}");
        Files.writeString(dir.resolve("readme.md"), "public docs here");
        ToolResult r = tool.execute(
                Map.of("pattern", "public", "path", dir.toString(), "include", "*.java"), ctx).block();
        assertThat(r.content()).contains("code.java");
        assertThat(r.content()).doesNotContain("readme.md");
    }

    @Test
    void maxResultsLimitsOutput(@TempDir Path dir) throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            content.append("match line ").append(i).append("\n");
        }
        Files.writeString(dir.resolve("many.txt"), content.toString());
        ToolResult r = tool.execute(
                Map.of("pattern", "match line", "path", dir.toString(), "maxResults", 3), ctx).block();
        long lineCount = r.content().lines().count();
        assertThat(lineCount).isLessThanOrEqualTo(3);
    }

    @Test
    void showsLineNumbers(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("numbered.txt"), "alpha\nbeta\ngamma\n");
        ToolResult r = tool.execute(
                Map.of("pattern", "beta", "path", dir.toString()), ctx).block();
        assertThat(r.content()).contains(":2:");
    }

    @Test
    void searchInSubdirectories(@TempDir Path dir) throws IOException {
        Path sub = dir.resolve("sub");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("deep.txt"), "deep content here");
        ToolResult r = tool.execute(
                Map.of("pattern", "deep content", "path", dir.toString()), ctx).block();
        assertThat(r.content()).contains("deep content");
    }
}
