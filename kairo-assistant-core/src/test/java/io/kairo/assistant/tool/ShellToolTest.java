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
    void missingCommandReturnsError() {
        ToolResult result = tool.execute(Map.of(), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("required");
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
    void infiniteOutputTruncatesAndKills() {
        ToolResult result = tool.execute(
                Map.of("command", "while true; do echo x; done", "timeout", 5),
                emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void blankCommandErrors() {
        ToolResult result = tool.execute(Map.of("command", "  "), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void emptyOutputReportsNoOutput() {
        ToolResult result = tool.execute(Map.of("command", "true"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("(no output)");
    }

    @Test
    void timeoutClampedToMax300() {
        ToolResult result = tool.execute(
                Map.of("command", "echo fast", "timeout", 999), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void exitCodeInMetadata() {
        ToolResult result = tool.execute(Map.of("command", "echo ok"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("exitCode", 0);
    }
}
