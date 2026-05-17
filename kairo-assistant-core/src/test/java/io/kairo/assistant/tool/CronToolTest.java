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
    void rejectsInvalidFieldCount() {
        ToolResult r = tool.execute(
                Map.of("action", "explain", "expression", "* * *"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("5-field");
    }

    @Test
    void nextAction() {
        ToolResult r = tool.execute(
                Map.of("action", "next", "expression", "30 14 * * *"), ctx).block();
        assertThat(r.isError()).isFalse();
    }
}
