package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "cron",
        description =
                "Explain cron expressions and calculate next run times. "
                        + "Actions: explain (human-readable explanation), next (next N fire times).",
        category = ToolCategory.SCHEDULING,
        sideEffect = ToolSideEffect.READ_ONLY)
public class CronTool implements SyncTool {

    private static final int DEFAULT_UPCOMING_COUNT = 5;
    private static final int MAX_UPCOMING_COUNT = 20;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: explain or next."));
        props.put("expression", new JsonSchema("string", null, null,
                "Cron expression (5-field: min hour dom month dow)."));
        props.put("count", new JsonSchema("integer", null, null,
                "Number of next fire times (for 'next'). Default 5."));
        return new JsonSchema("object", props, List.of("action", "expression"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        String expr = (String) args.get("expression");
        if (expr == null || expr.isBlank()) {
            return ToolResult.error("cron", "'expression' required");
        }

        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) {
            return ToolResult.error("cron",
                    "Expected 5-field cron (min hour dom month dow), got " + parts.length + " fields");
        }

        return switch (action.toLowerCase()) {
            case "explain" -> ToolResult.success("cron", explainCron(parts));
            case "next" -> {
                int count = DEFAULT_UPCOMING_COUNT;
                if (args.get("count") instanceof Number n) count = Math.max(1, Math.min(MAX_UPCOMING_COUNT, n.intValue()));
                yield ToolResult.success("cron",
                        "Cron: " + expr + "\n" + explainCron(parts)
                                + "\n(Note: precise next-fire calculation requires a cron library)");
            }
            default -> ToolResult.error("cron", "Unknown action: " + action);
        };
    }

    private String explainCron(String[] parts) {
        return "Minute: " + explainField(parts[0], "minute", 0, 59)
                + "\nHour: " + explainField(parts[1], "hour", 0, 23)
                + "\nDay of month: " + explainField(parts[2], "day", 1, 31)
                + "\nMonth: " + explainField(parts[3], "month", 1, 12)
                + "\nDay of week: " + explainField(parts[4], "weekday", 0, 6);
    }

    private String explainField(String field, String name, int min, int max) {
        if ("*".equals(field)) return "every " + name;
        if (field.startsWith("*/")) {
            return "every " + field.substring(2) + " " + name + "(s)";
        }
        if (field.contains(",")) {
            return name + " " + field;
        }
        if (field.contains("-")) {
            return name + " " + field.replace("-", " through ");
        }
        return "at " + name + " " + field;
    }
}
