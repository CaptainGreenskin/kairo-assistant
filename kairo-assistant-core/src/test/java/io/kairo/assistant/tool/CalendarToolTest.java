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

    @Test
    void businessDaysCountBetweenDates() {
        // Mon 2026-01-05 to Fri 2026-01-09 = 4 business days
        ToolResult result = tool.execute(
                Map.of("action", "business_days", "date", "2026-01-05", "date2", "2026-01-09"),
                ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("4");
    }

    @Test
    void businessDaysCountSkipsWeekends() {
        // Mon 2026-01-05 to Mon 2026-01-12 = 5 business days (skips Sat+Sun)
        ToolResult result = tool.execute(
                Map.of("action", "business_days", "date", "2026-01-05", "date2", "2026-01-12"),
                ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("5");
    }

    @Test
    void businessDaysAddForward() {
        // Add 5 business days from Mon 2026-01-05 = Mon 2026-01-12
        ToolResult result = tool.execute(
                Map.of("action", "business_days", "date", "2026-01-05", "days", 5),
                ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("2026-01-12");
    }

    @Test
    void weekNumber() {
        // 2026-01-01 is ISO week 1 of 2026
        ToolResult result = tool.execute(
                Map.of("action", "week_number", "date", "2026-01-01"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("week 1");
    }

    @Test
    void unknownActionErrors() {
        ToolResult result = tool.execute(Map.of("action", "lunar"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown action");
    }
}
