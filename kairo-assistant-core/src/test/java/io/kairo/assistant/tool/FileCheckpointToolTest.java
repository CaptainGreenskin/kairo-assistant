package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.workspace.Workspace;
import io.kairo.api.workspace.WorkspaceKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCheckpointToolTest {

    @TempDir
    Path tempDir;

    private FileCheckpointTool tool;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        tool = new FileCheckpointTool();
        exec(tempDir, "git", "init");
        exec(tempDir, "git", "config", "user.email", "test@test.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        Files.writeString(tempDir.resolve("file.txt"), "initial");
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "init");
    }

    @Test
    void createCheckpointSucceeds() {
        ToolResult result = tool.execute(
                Map.of("action", "create", "label", "cp1", "message", "before refactor"),
                ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("cp1");
    }

    @Test
    void listShowsCreatedCheckpoints() {
        tool.execute(Map.of("action", "create", "label", "alpha"), ctx()).block();
        tool.execute(Map.of("action", "create", "label", "beta"), ctx()).block();

        ToolResult result = tool.execute(Map.of("action", "list"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("alpha");
        assertThat(result.content()).contains("beta");
    }

    @Test
    void restoreRevertsChanges() throws IOException {
        tool.execute(Map.of("action", "create", "label", "before"), ctx()).block();

        Files.writeString(tempDir.resolve("file.txt"), "modified");

        ToolResult result = tool.execute(
                Map.of("action", "restore", "label", "before"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();

        String content = Files.readString(tempDir.resolve("file.txt"));
        assertThat(content).isEqualTo("initial");
    }

    @Test
    void deleteRemovesCheckpoint() {
        tool.execute(Map.of("action", "create", "label", "disposable"), ctx()).block();

        ToolResult result = tool.execute(
                Map.of("action", "delete", "label", "disposable"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();

        ToolResult list = tool.execute(Map.of("action", "list"), ctx()).block();
        assertThat(list.content()).doesNotContain("disposable");
    }

    @Test
    void restoreNonExistentReturnsError() {
        ToolResult result = tool.execute(
                Map.of("action", "restore", "label", "ghost"), ctx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("not found");
    }

    @Test
    void notGitRepoReturnsError() throws IOException {
        Path nonGit = Files.createTempDirectory("no-git");
        ToolContext nonGitCtx = ctxWithDir(nonGit);
        ToolResult result = tool.execute(Map.of("action", "list"), nonGitCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("git");
    }

    private ToolContext ctx() {
        return ctxWithDir(tempDir);
    }

    private ToolContext ctxWithDir(Path dir) {
        Workspace ws = new Workspace() {
            @Override public String id() { return "test"; }
            @Override public Path root() { return dir; }
            @Override public WorkspaceKind kind() { return WorkspaceKind.LOCAL; }
            @Override public Map<String, String> metadata() { return Map.of(); }
        };
        return new ToolContext("agent", "session", null, ws, null, null, Map.of());
    }

    private void exec(Path dir, String... cmd) throws IOException, InterruptedException {
        new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start().waitFor();
    }
}
