package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "time",
        description = "Get the current date and time, optionally in a specific timezone.",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class TimeTool implements SyncTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (EEEE)");

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put(
                "timezone",
                new JsonSchema(
                        "string",
                        null,
                        null,
                        "Timezone ID (e.g., 'Asia/Shanghai', 'US/Pacific'). Defaults to system timezone."));
        return new JsonSchema("object", props, List.of(), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> {
            String tz = (String) args.get("timezone");
            ZoneId zone;
            try {
                zone = (tz != null && !tz.isBlank()) ? ZoneId.of(tz) : ZoneId.systemDefault();
            } catch (Exception e) {
                return ToolResult.error("time", "Invalid timezone: " + tz);
            }
            ZonedDateTime now = ZonedDateTime.now(zone);
            return ToolResult.success("time", now.format(FORMATTER));
        });
    }
}
