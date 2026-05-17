package io.kairo.assistant.tool;

import io.kairo.api.cron.CronTask;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.core.cron.CronScheduler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "reminder",
        description =
                "Create, list, or delete scheduled reminders. "
                        + "Use cron expressions for scheduling (e.g., '30 14 * * *' = daily 2:30pm).",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.WRITE)
public class ReminderTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put(
                "action",
                new JsonSchema("string", null, null, "Action: create, list, or delete."));
        props.put(
                "cron",
                new JsonSchema(
                        "string",
                        null,
                        null,
                        "5-field cron expression (for create). E.g. '0 9 * * 1-5' = weekdays 9am."));
        props.put(
                "message",
                new JsonSchema("string", null, null, "Reminder message (for create)."));
        props.put(
                "recurring",
                new JsonSchema(
                        "boolean", null, null, "Whether recurring (default true for create)."));
        props.put(
                "id",
                new JsonSchema("string", null, null, "Reminder id (for delete)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> {
            CronScheduler scheduler = resolveScheduler(ctx);
            if (scheduler == null) {
                return ToolResult.error("reminder", "CronScheduler not available");
            }

            String action = (String) args.getOrDefault("action", "list");
            return switch (action) {
                case "create" -> doCreate(args, scheduler);
                case "list" -> doList(scheduler);
                case "delete" -> doDelete(args, scheduler);
                default -> ToolResult.error("reminder", "Unknown action: " + action);
            };
        });
    }

    private ToolResult doCreate(Map<String, Object> args, CronScheduler scheduler) {
        String cron = (String) args.get("cron");
        String message = (String) args.get("message");
        if (cron == null || cron.isBlank()) {
            return ToolResult.error("reminder", "Parameter 'cron' is required");
        }
        if (message == null || message.isBlank()) {
            return ToolResult.error("reminder", "Parameter 'message' is required");
        }
        boolean recurring = !Boolean.FALSE.equals(args.get("recurring"));

        CronTask task = scheduler.create(cron, message, recurring, true);
        return ToolResult.success(
                "reminder",
                "Created reminder [" + task.id() + "]: \"" + message + "\" schedule=" + cron
                        + " recurring=" + recurring);
    }

    private ToolResult doList(CronScheduler scheduler) {
        List<CronTask> tasks = scheduler.list();
        if (tasks.isEmpty()) {
            return ToolResult.success("reminder", "No active reminders.");
        }
        StringBuilder sb = new StringBuilder(tasks.size() + " reminder(s):\n");
        for (CronTask t : tasks) {
            sb.append("- [")
                    .append(t.id())
                    .append("] \"")
                    .append(t.prompt())
                    .append("\" cron=")
                    .append(t.cron())
                    .append(" recurring=")
                    .append(t.recurring())
                    .append("\n");
        }
        return ToolResult.success("reminder", sb.toString().trim());
    }

    private ToolResult doDelete(Map<String, Object> args, CronScheduler scheduler) {
        String id = (String) args.get("id");
        if (id == null || id.isBlank()) {
            return ToolResult.error("reminder", "Parameter 'id' is required for delete");
        }
        boolean deleted = scheduler.delete(id);
        return deleted
                ? ToolResult.success("reminder", "Deleted reminder: " + id)
                : ToolResult.error("reminder", "Reminder not found: " + id);
    }

    private CronScheduler resolveScheduler(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object sched = ctx.dependencies().get("cronScheduler");
        return sched instanceof CronScheduler cs ? cs : null;
    }
}
