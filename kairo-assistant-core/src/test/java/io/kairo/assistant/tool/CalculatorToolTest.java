package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void basicArithmetic() {
        ToolResult result = tool.execute(Map.of("expression", "2 + 3 * 4"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("14");
    }

    @Test
    void parentheses() {
        ToolResult result = tool.execute(Map.of("expression", "(2 + 3) * 4"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("20");
    }

    @Test
    void sqrtFunction() {
        ToolResult result = tool.execute(Map.of("expression", "Math.sqrt(144)"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("12");
    }

    @Test
    void power() {
        ToolResult result = tool.execute(Map.of("expression", "2 ^ 10"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("1024");
    }
}
