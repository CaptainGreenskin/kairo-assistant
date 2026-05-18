package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitToolTest {

    private final GitTool tool = new GitTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void statusInCurrentDir() {
        ToolResult r = tool.execute(Map.of("action", "status"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void logShowsCommits() {
        ToolResult r = tool.execute(Map.of("action", "log", "args", "-n 3"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void branchListsAll() {
        ToolResult r = tool.execute(Map.of("action", "branch"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("main");
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "push"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of("directory", "."), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void diffInCurrentDir() {
        ToolResult r = tool.execute(Map.of("action", "diff"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void logWithDefaultArgs() {
        ToolResult r = tool.execute(Map.of("action", "log"), ctx).block();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void showLatestCommit() {
        ToolResult r = tool.execute(Map.of("action", "show", "args", "--stat HEAD"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).isNotBlank();
    }

    @Test
    void statusInExplicitDir() {
        ToolResult r = tool.execute(Map.of("action", "status", "directory", "."), ctx).block();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void invalidDirectoryErrors() {
        ToolResult r = tool.execute(Map.of("action", "status", "directory", "/nonexistent_dir_xyz"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void blankActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void exitCodeInMetadata() {
        ToolResult r = tool.execute(Map.of("action", "status"), ctx).block();
        assertThat(r.metadata()).containsEntry("exitCode", 0);
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("action");
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("directory");
        assertThat(schema.properties()).containsKey("args");
    }

    @Test
    void toolAnnotation() {
        var ann = GitTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("git");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.FILE_AND_CODE);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.READ_ONLY);
    }
}
