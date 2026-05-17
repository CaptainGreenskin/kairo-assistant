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

class WriteFileToolTest {

    private final WriteFileTool tool = new WriteFileTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void writeCreatesFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("output.txt");
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "content", "hello world"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file)).isEqualTo("hello world");
    }

    @Test
    void writeCreatesParentDirs(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("sub/dir/output.txt");
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "content", "nested"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file)).isEqualTo("nested");
    }

    @Test
    void writeOverwritesExistingFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("existing.txt");
        Files.writeString(file, "old content");
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "content", "new content"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file)).isEqualTo("new content");
    }

    @Test
    void writeEmptyContent(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("empty.txt");
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "content", ""), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file)).isEmpty();
    }

    @Test
    void rejectsMissingPath() {
        ToolResult result = tool.execute(Map.of("content", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void toolAnnotationAndSchema() {
        var annotation = WriteFileTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("write_file");
        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.inputSchema().required()).contains("path");
    }
}
