package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CalendarToolTest {

    private final CalendarTool tool = new CalendarTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void todayReturnsCurrentDate() {
        ToolResult result = tool.execute(Map.of("action", "today"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains(LocalDate.now().toString());
    }

    @Test
    void dayOfWeek() {
        ToolResult result = tool.execute(
                Map.of("action", "day_of_week", "date", "2026-01-01"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("THURSDAY");
    }

    @Test
    void diffBetweenDates() {
        ToolResult result = tool.execute(
                Map.of("action", "diff", "date", "2026-01-01", "date2", "2026-01-10"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("9 days");
    }

    @Test
    void addDays() {
        ToolResult result = tool.execute(
                Map.of("action", "add", "date", "2026-01-01", "days", 7), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("2026-01-08");
    }

    @Test
    void weekdayCheck() {
        ToolResult result = tool.execute(
                Map.of("action", "weekday_check", "date", "2026-05-16"), ctx).block();
        assertThat(result).isNotNull();
        // 2026-05-16 is a Saturday
        assertThat(result.content()).contains("weekend");
    }
}
