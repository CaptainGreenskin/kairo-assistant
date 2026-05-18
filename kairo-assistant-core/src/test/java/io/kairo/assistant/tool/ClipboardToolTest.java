package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClipboardToolTest {

    private final ClipboardTool tool = new ClipboardTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of("content", "hello"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void writeRequiresContent() {
        ToolResult r = tool.execute(Map.of("action", "write"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("content");
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "paste"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void readDoesNotError() {
        ToolResult r = tool.execute(Map.of("action", "read"), ctx).block();
        assertThat(r).isNotNull();
    }

    @Test
    void writeAndReadRoundTrip() {
        String testContent = "kairo-test-" + System.currentTimeMillis();
        ToolResult write = tool.execute(Map.of("action", "write", "content", testContent), ctx).block();
        assertThat(write.isError()).isFalse();
        assertThat(write.content()).contains("chars to clipboard");

        ToolResult read = tool.execute(Map.of("action", "read"), ctx).block();
        assertThat(read.isError()).isFalse();
        assertThat(read.content()).contains(testContent);
    }

    @Test
    void writeReportsCharCount() {
        ToolResult r = tool.execute(Map.of("action", "write", "content", "12345"), ctx).block();
        assertThat(r.content()).contains("5 chars");
    }

    @Test
    void caseInsensitiveAction() {
        ToolResult r = tool.execute(Map.of("action", "READ"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void schemaHasActionAndContent() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("content");
        assertThat(schema.required()).containsExactly("action");
    }

    @Test
    void toolAnnotation() {
        var annotation = ClipboardTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("clipboard");
        assertThat(annotation.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }

    @Test
    void writeEmptyStringSucceeds() {
        ToolResult r = tool.execute(Map.of("action", "write", "content", ""), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("0 chars");
    }
}
