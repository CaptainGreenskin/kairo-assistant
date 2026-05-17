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

class PatchToolTest {

    private final PatchTool tool = new PatchTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void singleReplacement(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("test.txt"), "Hello World");
        ToolResult r = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "World",
                "new_string", "Kairo"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(Files.readString(file)).isEqualTo("Hello Kairo");
    }

    @Test
    void ambiguousMatchErrors(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("dup.txt"), "aa bb aa");
        ToolResult r = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "aa",
                "new_string", "cc"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("2 times");
    }

    @Test
    void replaceAllFlag(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("multi.txt"), "aa bb aa");
        ToolResult r = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "aa",
                "new_string", "cc",
                "replace_all", true), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(Files.readString(file)).isEqualTo("cc bb cc");
    }

    @Test
    void notFoundErrors(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("nf.txt"), "hello");
        ToolResult r = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "missing",
                "new_string", "x"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void missingFileErrors() {
        ToolResult r = tool.execute(Map.of(
                "path", "/nonexistent_xyz",
                "old_string", "a",
                "new_string", "b"), ctx).block();
        assertThat(r.isError()).isTrue();
    }
}
