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

class ReadFileToolTest {

    private final ReadFileTool tool = new ReadFileTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void readExistingFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3");
        ToolResult result = tool.execute(Map.of("path", file.toString()), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("line1").contains("line2").contains("line3");
    }

    @Test
    void readNonExistentFile() {
        ToolResult result = tool.execute(Map.of("path", "/nonexistent/file.txt"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void readWithOffset(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("test.txt");
        Files.writeString(file, "line0\nline1\nline2\nline3\nline4");
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "offset", 2, "limit", 2), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("line2").contains("line3");
        assertThat(result.content()).doesNotContain("line0").doesNotContain("line1");
    }

    @Test
    void readDirectoryReturnsError(@TempDir Path tmp) {
        ToolResult result = tool.execute(Map.of("path", tmp.toString()), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("directory");
    }

    @Test
    void readEmptyPathReturnsError() {
        ToolResult result = tool.execute(Map.of("path", ""), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void readWithOffsetPastEnd(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("small.txt");
        Files.writeString(file, "just one line");
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "offset", 100), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("past end");
    }

    @Test
    void readShowsMoreLinesIndicator(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("many.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("line").append(i).append("\n");
        Files.writeString(file, sb.toString());
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "limit", 5), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("more lines available");
    }
}
