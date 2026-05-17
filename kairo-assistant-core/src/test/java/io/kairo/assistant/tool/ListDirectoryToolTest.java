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
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("hello.txt").contains("subdir/");
    }

    @Test
    void nonExistentPathErrors() {
        ToolResult r = tool.execute(Map.of("path", "/nonexistent_xyz_123"), ctx).block();
        assertThat(r.isError()).isTrue();
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
}
