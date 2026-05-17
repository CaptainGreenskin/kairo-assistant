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
}
