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

    @Test
    void missingPathParam() {
        ToolResult r = tool.execute(Map.of(
                "old_string", "a", "new_string", "b"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("path");
    }

    @Test
    void missingOldStringParam() {
        ToolResult r = tool.execute(Map.of(
                "path", "/tmp/x", "new_string", "b"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("old_string");
    }

    @Test
    void missingNewStringParam() {
        ToolResult r = tool.execute(Map.of(
                "path", "/tmp/x", "old_string", "a"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("new_string");
    }

    @Test
    void replacePreservesRestOfFile(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("ctx.txt"), "line1\nfoo\nline3");
        ToolResult r = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "foo",
                "new_string", "bar"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(Files.readString(file)).isEqualTo("line1\nbar\nline3");
    }

    @Test
    void replaceAllReturnsCount(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("cnt.txt"), "x x x");
        ToolResult r = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "x",
                "new_string", "y",
                "replace_all", true), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("3 occurrence");
        assertThat(Files.readString(file)).isEqualTo("y y y");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("path", "old_string", "new_string");
        assertThat(schema.properties()).containsKey("replace_all");
        assertThat(schema.type()).isEqualTo("object");
    }

    @Test
    void toolAnnotation() {
        var ann = PatchTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("patch");
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }
}
