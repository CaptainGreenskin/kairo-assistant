package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "calendar",
        description =
                "Date and calendar utilities. Actions: today, day_of_week, diff (days between dates), "
                        + "add (add days to a date), weekday_check.",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class CalendarTool implements SyncTool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: today, day_of_week, diff, add, weekday_check."));
        props.put("date", new JsonSchema("string", null, null,
                "Date in YYYY-MM-DD format."));
        props.put("date2", new JsonSchema("string", null, null,
                "Second date for diff (YYYY-MM-DD)."));
        props.put("days", new JsonSchema("integer", null, null,
                "Number of days to add (can be negative)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) {
            return ToolResult.error("calendar", "'action' required");
        }

        return switch (action.toLowerCase()) {
            case "today" -> {
                LocalDate today = LocalDate.now();
                yield ToolResult.success("calendar",
                        "Today: " + today.format(FMT) + " (" + today.getDayOfWeek() + ")");
            }
            case "day_of_week" -> {
                LocalDate d = parseDate(args, "date");
                if (d == null) yield ToolResult.error("calendar", "'date' required (YYYY-MM-DD)");
                else yield ToolResult.success("calendar",
                        d.format(FMT) + " is " + d.getDayOfWeek());
            }
            case "diff" -> {
                LocalDate d1 = parseDate(args, "date");
                LocalDate d2 = parseDate(args, "date2");
                if (d1 == null || d2 == null) {
                    yield ToolResult.error("calendar", "'date' and 'date2' required");
                }
                long days = ChronoUnit.DAYS.between(d1, d2);
                yield ToolResult.success("calendar",
                        "From " + d1.format(FMT) + " to " + d2.format(FMT) + ": " + days + " days");
            }
            case "add" -> {
                LocalDate d = parseDate(args, "date");
                if (d == null) d = LocalDate.now();
                int days = 0;
                if (args.get("days") instanceof Number n) days = n.intValue();
                LocalDate result = d.plusDays(days);
                yield ToolResult.success("calendar",
                        d.format(FMT) + " + " + days + " days = " + result.format(FMT)
                                + " (" + result.getDayOfWeek() + ")");
            }
            case "weekday_check" -> {
                LocalDate d = parseDate(args, "date");
                if (d == null) d = LocalDate.now();
                DayOfWeek dow = d.getDayOfWeek();
                boolean isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
                yield ToolResult.success("calendar",
                        d.format(FMT) + " (" + dow + ") is a " + (isWeekday ? "weekday" : "weekend"));
            }
            default -> ToolResult.error("calendar", "Unknown action: " + action);
        };
    }

    private LocalDate parseDate(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof String s && !s.isBlank()) {
            try {
                return LocalDate.parse(s, FMT);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
