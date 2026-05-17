package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "calculator",
        description =
                "Evaluate mathematical expressions. Supports basic arithmetic (+, -, *, /), "
                        + "power (Math.pow), sqrt (Math.sqrt), trigonometry, and constants (Math.PI, Math.E).",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.READ_ONLY)
public class CalculatorTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("expression", new JsonSchema("string", null, null,
                "Math expression to evaluate (e.g. '2 + 3 * 4', 'Math.sqrt(144)')."));
        return new JsonSchema("object", props, List.of("expression"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String expression = (String) args.get("expression");
        if (expression == null || expression.isBlank()) {
            return ToolResult.error("calculator", "'expression' required");
        }

        try {
            double result = evaluate(expression);
            String formatted;
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                formatted = String.valueOf((long) result);
            } else {
                formatted = String.valueOf(result);
            }
            return ToolResult.success("calculator",
                    expression + " = " + formatted);
        } catch (Exception e) {
            return ToolResult.error("calculator",
                    "Failed to evaluate: " + e.getMessage());
        }
    }

    private double evaluate(String expr) {
        return new ExpressionParser(expr.trim()).parse();
    }

    private static class ExpressionParser {
        private final String input;
        private int pos;

        ExpressionParser(String input) {
            this.input = input.trim();
            this.pos = 0;
        }

        double parse() {
            double result = parseExpression();
            if (pos < input.length()) {
                throw new IllegalArgumentException("Unexpected character at position " + pos);
            }
            return result;
        }

        private double parseExpression() {
            double result = parseTerm();
            while (pos < input.length()) {
                skipSpaces();
                if (pos >= input.length()) break;
                char c = input.charAt(pos);
                if (c == '+') { pos++; result += parseTerm(); }
                else if (c == '-') { pos++; result -= parseTerm(); }
                else break;
            }
            return result;
        }

        private double parseTerm() {
            double result = parsePower();
            while (pos < input.length()) {
                skipSpaces();
                if (pos >= input.length()) break;
                char c = input.charAt(pos);
                if (c == '*') { pos++; result *= parsePower(); }
                else if (c == '/') { pos++; result /= parsePower(); }
                else if (c == '%') { pos++; result %= parsePower(); }
                else break;
            }
            return result;
        }

        private double parsePower() {
            double base = parseUnary();
            skipSpaces();
            if (pos < input.length() && input.charAt(pos) == '^') {
                pos++;
                base = Math.pow(base, parsePower());
            }
            return base;
        }

        private double parseUnary() {
            skipSpaces();
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
                return -parseAtom();
            }
            if (pos < input.length() && input.charAt(pos) == '+') {
                pos++;
            }
            return parseAtom();
        }

        private double parseAtom() {
            skipSpaces();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }

            if (input.charAt(pos) == '(') {
                pos++;
                double result = parseExpression();
                skipSpaces();
                if (pos < input.length() && input.charAt(pos) == ')') pos++;
                return result;
            }

            if (input.startsWith("Math.PI", pos)) { pos += 7; return Math.PI; }
            if (input.startsWith("Math.E", pos)) { pos += 6; return Math.E; }
            if (input.startsWith("Math.sqrt(", pos)) { return parseMathFunc("Math.sqrt(", 10); }
            if (input.startsWith("Math.pow(", pos)) { return parseMathPow(); }
            if (input.startsWith("Math.log(", pos)) { return parseMathFunc("Math.log(", 9); }
            if (input.startsWith("Math.sin(", pos)) { return parseMathTrig("Math.sin(", 9, Math::sin); }
            if (input.startsWith("Math.cos(", pos)) { return parseMathTrig("Math.cos(", 9, Math::cos); }
            if (input.startsWith("Math.tan(", pos)) { return parseMathTrig("Math.tan(", 9, Math::tan); }
            if (input.startsWith("sqrt(", pos)) { return parseMathFunc("sqrt(", 5); }

            return parseNumber();
        }

        private double parseMathFunc(String prefix, int prefixLen) {
            pos += prefixLen;
            double arg = parseExpression();
            skipSpaces();
            if (pos < input.length() && input.charAt(pos) == ')') pos++;
            if (prefix.contains("sqrt")) return Math.sqrt(arg);
            if (prefix.contains("log")) return Math.log(arg);
            return arg;
        }

        private double parseMathPow() {
            pos += 9;
            double base = parseExpression();
            skipSpaces();
            if (pos < input.length() && input.charAt(pos) == ',') pos++;
            double exp = parseExpression();
            skipSpaces();
            if (pos < input.length() && input.charAt(pos) == ')') pos++;
            return Math.pow(base, exp);
        }

        private double parseMathTrig(String prefix, int prefixLen,
                                     java.util.function.DoubleUnaryOperator fn) {
            pos += prefixLen;
            double arg = parseExpression();
            skipSpaces();
            if (pos < input.length() && input.charAt(pos) == ')') pos++;
            return fn.applyAsDouble(arg);
        }

        private double parseNumber() {
            skipSpaces();
            int start = pos;
            while (pos < input.length()
                    && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("Expected number at position " + pos);
            }
            return Double.parseDouble(input.substring(start, pos));
        }

        private void skipSpaces() {
            while (pos < input.length() && input.charAt(pos) == ' ') pos++;
        }
    }
}
