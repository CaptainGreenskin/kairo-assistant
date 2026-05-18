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

    @Test
    void expressionRequired() {
        ToolResult r = tool.execute(Map.of(), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void blankExpressionErrors() {
        ToolResult r = tool.execute(Map.of("expression", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void division() {
        ToolResult r = tool.execute(Map.of("expression", "100 / 4"), ctx).block();
        assertThat(r.content()).contains("25");
    }

    @Test
    void modulo() {
        ToolResult r = tool.execute(Map.of("expression", "17 % 5"), ctx).block();
        assertThat(r.content()).contains("2");
    }

    @Test
    void divisionByZeroProducesInfinity() {
        ToolResult r = tool.execute(Map.of("expression", "1 / 0"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Infinity");
    }

    @Test
    void mathPi() {
        ToolResult r = tool.execute(Map.of("expression", "Math.PI"), ctx).block();
        assertThat(r.content()).contains("3.14");
    }

    @Test
    void mathE() {
        ToolResult r = tool.execute(Map.of("expression", "Math.E"), ctx).block();
        assertThat(r.content()).contains("2.71");
    }

    @Test
    void mathPow() {
        ToolResult r = tool.execute(Map.of("expression", "Math.pow(3, 4)"), ctx).block();
        assertThat(r.content()).contains("81");
    }

    @Test
    void mathLog() {
        ToolResult r = tool.execute(Map.of("expression", "Math.log(1)"), ctx).block();
        assertThat(r.content()).contains("0");
    }

    @Test
    void mathSin() {
        ToolResult r = tool.execute(Map.of("expression", "Math.sin(0)"), ctx).block();
        assertThat(r.content()).contains("0");
    }

    @Test
    void mathCos() {
        ToolResult r = tool.execute(Map.of("expression", "Math.cos(0)"), ctx).block();
        assertThat(r.content()).contains("1");
    }

    @Test
    void mathTan() {
        ToolResult r = tool.execute(Map.of("expression", "Math.tan(0)"), ctx).block();
        assertThat(r.content()).contains("0");
    }

    @Test
    void sqrtWithoutMathPrefix() {
        ToolResult r = tool.execute(Map.of("expression", "sqrt(49)"), ctx).block();
        assertThat(r.content()).contains("7");
    }

    @Test
    void unaryMinus() {
        ToolResult r = tool.execute(Map.of("expression", "-5 + 3"), ctx).block();
        assertThat(r.content()).contains("-2");
    }

    @Test
    void unaryPlus() {
        ToolResult r = tool.execute(Map.of("expression", "+7 * 2"), ctx).block();
        assertThat(r.content()).contains("14");
    }

    @Test
    void decimalNumbers() {
        ToolResult r = tool.execute(Map.of("expression", "3.5 * 2"), ctx).block();
        assertThat(r.content()).contains("7");
    }

    @Test
    void integerResultFormattedWithoutDecimal() {
        ToolResult r = tool.execute(Map.of("expression", "10 + 5"), ctx).block();
        assertThat(r.content()).contains("= 15");
        assertThat(r.content()).doesNotContain("15.0");
    }

    @Test
    void invalidExpressionErrors() {
        ToolResult r = tool.execute(Map.of("expression", "abc"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Failed to evaluate");
    }

    @Test
    void nestedParentheses() {
        ToolResult r = tool.execute(Map.of("expression", "((2 + 3) * (4 - 1))"), ctx).block();
        assertThat(r.content()).contains("15");
    }

    @Test
    void complexExpression() {
        ToolResult r = tool.execute(Map.of("expression", "2 ^ 3 + Math.sqrt(16) * 2"), ctx).block();
        assertThat(r.content()).contains("16");
    }
}
