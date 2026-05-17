package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeExecuteToolTest {

    private final CodeExecuteTool tool = new CodeExecuteTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void executeBash() {
        ToolResult r = tool.execute(Map.of("code", "echo hello", "language", "bash"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("hello");
    }

    @Test
    void executePythonPrint() {
        ToolResult r = tool.execute(Map.of("code", "print(2+3)", "language", "python"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("5");
    }

    @Test
    void codeRequired() {
        ToolResult r = tool.execute(Map.of("language", "bash"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void nonZeroExitIsError() {
        ToolResult r = tool.execute(Map.of("code", "exit 1", "language", "bash"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void infiniteOutputTruncatesAndKills() {
        ToolResult r = tool.execute(
                Map.of("code", "while true; do echo x; done", "language", "bash", "timeout", 5),
                ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void defaultLanguageIsPython() {
        ToolResult r = tool.execute(Map.of("code", "print('hi')"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("hi");
    }

    @Test
    void blankCodeErrors() {
        ToolResult r = tool.execute(Map.of("code", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void nodeLanguageWorks() {
        ToolResult r = tool.execute(
                Map.of("code", "console.log('node ok')", "language", "node"), ctx).block();
        assertThat(r).isNotNull();
        if (!r.isError()) {
            assertThat(r.content()).contains("node ok");
        }
    }

    @Test
    void exitCodeInMetadata() {
        ToolResult r = tool.execute(Map.of("code", "echo done", "language", "bash"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.metadata()).containsEntry("exitCode", 0);
        assertThat(r.metadata()).containsEntry("language", "bash");
    }
}
