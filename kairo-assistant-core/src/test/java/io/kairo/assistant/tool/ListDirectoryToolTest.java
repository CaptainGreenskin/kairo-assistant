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

class ListDirectoryToolTest {

    private final ListDirectoryTool tool = new ListDirectoryTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void listsFilesInDirectory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("hello.txt"), "world");
        Files.createDirectory(dir.resolve("subdir"));

        ToolResult r = tool.execute(Map.of("path", dir.toString()), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("hello.txt").contains("subdir/");
    }

    @Test
    void nonExistentPathErrors() {
        ToolResult r = tool.execute(Map.of("path", "/nonexistent_xyz_123"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("not found");
    }

    @Test
    void fileNotDirErrors(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("f.txt"), "data");
        ToolResult r = tool.execute(Map.of("path", file.toString()), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Not a directory");
    }

    @Test
    void recursiveListing(@TempDir Path dir) throws IOException {
        Path sub = Files.createDirectory(dir.resolve("child"));
        Files.writeString(sub.resolve("nested.txt"), "hi");

        ToolResult r = tool.execute(
                Map.of("path", dir.toString(), "recursive", true), ctx).block();
        assertThat(r.content()).contains("child/").contains("nested.txt");
    }

    @Test
    void showsFileSizes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("sized.txt"), "some content here");
        ToolResult r = tool.execute(Map.of("path", dir.toString()), ctx).block();
        assertThat(r.content()).contains("sized.txt").contains("B");
    }

    @Test
    void defaultPathIsCurrentDir() {
        ToolResult r = tool.execute(Map.of(), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).isNotBlank();
    }

    @Test
    void sortedAlphabetically(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("c.txt"), "c");
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.txt"), "b");
        ToolResult r = tool.execute(Map.of("path", dir.toString()), ctx).block();
        int posA = r.content().indexOf("a.txt");
        int posB = r.content().indexOf("b.txt");
        int posC = r.content().indexOf("c.txt");
        assertThat(posA).isLessThan(posB);
        assertThat(posB).isLessThan(posC);
    }

    @Test
    void emptyDirectory(@TempDir Path dir) {
        ToolResult r = tool.execute(Map.of("path", dir.toString()), ctx).block();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void maxDepthLimitsRecursion(@TempDir Path dir) throws IOException {
        Path l1 = Files.createDirectory(dir.resolve("l1"));
        Path l2 = Files.createDirectory(l1.resolve("l2"));
        Path l3 = Files.createDirectory(l2.resolve("l3"));
        Files.writeString(l3.resolve("deep.txt"), "deep");

        ToolResult r = tool.execute(
                Map.of("path", dir.toString(), "recursive", true, "maxDepth", 1), ctx).block();
        assertThat(r.content()).contains("l1/");
        assertThat(r.content()).contains("l2/");
        assertThat(r.content()).doesNotContain("deep.txt");
    }

    @Test
    void blankPathUsesCurrentDir() {
        ToolResult r = tool.execute(Map.of("path", ""), ctx).block();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void maxDepthClampedToLimit(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("root.txt"), "r");
        ToolResult r = tool.execute(
                Map.of("path", dir.toString(), "recursive", true, "maxDepth", 999), ctx).block();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("path");
        assertThat(schema.properties()).containsKey("recursive");
        assertThat(schema.properties()).containsKey("maxDepth");
        assertThat(schema.required()).isEmpty();
    }

    @Test
    void toolAnnotation() {
        var ann = ListDirectoryTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("list_directory");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.FILE_AND_CODE);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.READ_ONLY);
    }
}
