package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CronToolTest {

    private final CronTool tool = new CronTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void explainStandardCron() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "0 9 * * 1-5"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Minute").contains("Hour").contains("weekday");
    }

    @Test
    void explainEveryFiveMinutes() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "*/5 * * * *"), ctx).block();
        assertThat(r.content()).contains("every 5");
    }

    @Test
    void explainAllStars() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "* * * * *"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("every minute");
        assertThat(r.content()).contains("every hour");
    }

    @Test
    void explainSpecificValues() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "30 14 1 6 0"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("at minute 30");
        assertThat(r.content()).contains("at hour 14");
    }

    @Test
    void explainRangeField() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "0 9 * * 1-5"), ctx).block();
        assertThat(r.content()).contains("1 through 5");
    }

    @Test
    void explainCommaField() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "0 9 * * 1,3,5"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("1,3,5");
    }

    @Test
    void rejectsInvalidFieldCount() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "* * *"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("5-field");
    }

    @Test
    void rejectsSixFields() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "0 0 * * * 2026"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("5-field");
    }

    @Test
    void nextAction() {
        ToolResult r = tool.execute(
                Map.of("action", "next", "expression", "30 14 * * *"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("Cron: 30 14 * * *");
    }

    @Test
    void expressionRequired() {
        ToolResult r = tool.execute(Map.of("action", "explain"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("'expression' required");
    }

    @Test
    void blankExpressionErrors() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(
                Map.of("action", "validate", "expression", "* * * * *"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("action", "expression");
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("expression");
        assertThat(schema.properties()).containsKey("count");
    }

    @Test
    void toolAnnotation() {
        var ann = CronTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("cron");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.SCHEDULING);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.READ_ONLY);
    }
}
