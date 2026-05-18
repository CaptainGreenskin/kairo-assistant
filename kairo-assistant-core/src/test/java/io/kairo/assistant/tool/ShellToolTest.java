package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShellToolTest {

    private final ShellTool tool = new ShellTool();

    private static ToolContext emptyCtx() {
        return new ToolContext("test-agent", "test-session", null);
    }

    @Test
    void executesSimpleCommand() {
        ToolResult result = tool.execute(Map.of("command", "echo hello"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("hello");
    }

    @Test
    void reportsNonZeroExitCode() {
        ToolResult result =
                tool.execute(Map.of("command", "exit 42"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Exit code 42");
    }

    @Test
    void errorExitCodeInMetadata() {
        ToolResult result = tool.execute(Map.of("command", "exit 7"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("exitCode", 7);
    }

    @Test
    void missingCommandReturnsError() {
        ToolResult result = tool.execute(Map.of(), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("required");
    }

    @Test
    void blankCommandErrors() {
        ToolResult result = tool.execute(Map.of("command", "  "), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void respectsWorkingDir() {
        ToolResult result =
                tool.execute(
                                Map.of("command", "pwd", "workingDir", "/tmp"),
                                emptyCtx())
                        .block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("/tmp");
    }

    @Test
    void blankWorkingDirIgnored() {
        ToolResult result = tool.execute(
                Map.of("command", "echo wd", "workingDir", "  "), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("wd");
    }

    @Test
    void emptyOutputReportsNoOutput() {
        ToolResult result = tool.execute(Map.of("command", "true"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("(no output)");
    }

    @Test
    void exitCodeInMetadata() {
        ToolResult result = tool.execute(Map.of("command", "echo ok"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("exitCode", 0);
    }

    @Test
    void timeoutClampedToMax300() {
        ToolResult result = tool.execute(
                Map.of("command", "echo fast", "timeout", 999), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void stderrCaptured() {
        ToolResult result = tool.execute(
                Map.of("command", "echo errtest >&2; exit 1"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("errtest");
    }

    @Test
    void multilineOutput() {
        ToolResult result = tool.execute(
                Map.of("command", "echo line1; echo line2; echo line3"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("line1").contains("line2").contains("line3");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("command");
        assertThat(schema.properties()).containsKey("command");
        assertThat(schema.properties()).containsKey("timeout");
        assertThat(schema.properties()).containsKey("workingDir");
    }

    @Test
    void toolAnnotation() {
        var ann = ShellTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("shell");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.EXECUTION);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }
}
