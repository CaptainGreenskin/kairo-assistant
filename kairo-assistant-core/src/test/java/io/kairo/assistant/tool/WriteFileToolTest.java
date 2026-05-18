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
        assertThat(result.content()).contains("path");
    }

    @Test
    void blankPathErrors() {
        ToolResult result = tool.execute(Map.of("path", "  ", "content", "x"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void nullContentWritesEmpty(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("null-content.txt");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("path", file.toString());
        args.put("content", null);
        ToolResult result = tool.execute(args, ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file)).isEmpty();
    }

    @Test
    void metadataContainsBytes(@TempDir Path tmp) {
        Path file = tmp.resolve("meta.txt");
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "content", "abc"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("bytes", 3L);
    }

    @Test
    void resultMessageContainsByteCount(@TempDir Path tmp) {
        Path file = tmp.resolve("msg.txt");
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "content", "12345"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("5 bytes");
    }

    @Test
    void unicodeContentCorrectBytes(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("unicode.txt");
        String content = "你好世界";
        ToolResult result = tool.execute(
                Map.of("path", file.toString(), "content", content), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(file)).isEqualTo(content);
        assertThat(result.content()).contains("12 bytes");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("path", "content");
        assertThat(schema.properties()).containsKey("path");
        assertThat(schema.properties()).containsKey("content");
    }

    @Test
    void toolAnnotation() {
        var ann = WriteFileTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("write_file");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.FILE_AND_CODE);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }
}
