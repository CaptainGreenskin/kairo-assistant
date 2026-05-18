package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimeToolTest {

    private final TimeTool tool = new TimeTool();

    private static ToolContext emptyCtx() {
        return new ToolContext("test-agent", "test-session", null);
    }

    @Test
    void returnsCurrentTimeInSystemTimezone() {
        ToolResult result = tool.execute(Map.of(), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("202");
    }

    @Test
    void returnsTimeInSpecificTimezone() {
        ToolResult result =
                tool.execute(Map.of("timezone", "Asia/Shanghai"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("CST").contains("202");
    }

    @Test
    void invalidTimezoneReturnsError() {
        ToolResult result =
                tool.execute(Map.of("timezone", "Nowhere/Invalid"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Invalid timezone");
    }

    @Test
    void returnsTimeInUtc() {
        ToolResult result = tool.execute(Map.of("timezone", "UTC"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("UTC");
    }

    @Test
    void returnsTimeInUSPacific() {
        ToolResult result = tool.execute(Map.of("timezone", "US/Pacific"), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("202");
    }

    @Test
    void blankTimezoneUsesSystemDefault() {
        ToolResult result = tool.execute(Map.of("timezone", ""), emptyCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void containsDayOfWeek() {
        ToolResult result = tool.execute(Map.of(), emptyCtx()).block();
        assertThat(result.content()).matches(".*\\(.+\\).*");
    }

    @Test
    void inputSchemaHasTimezoneField() {
        var schema = tool.inputSchema();
        assertThat(schema.type()).isEqualTo("object");
        assertThat(schema.properties()).containsKey("timezone");
        assertThat(schema.required()).isEmpty();
    }

    @Test
    void toolAnnotation() {
        var ann = TimeTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("time");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.INFORMATION);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.READ_ONLY);
    }
}
