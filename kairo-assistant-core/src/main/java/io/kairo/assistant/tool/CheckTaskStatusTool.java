package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "check_task_status",
        description =
                "Check the status of delegated sub-agent tasks. "
                        + "Use task_id to check a specific task, or omit to list all tasks in this session.",
        category = ToolCategory.AGENT_AND_TASK,
        sideEffect = ToolSideEffect.READ_ONLY)
public class CheckTaskStatusTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("task_id", new JsonSchema("string", null, null,
                "Specific task ID to check. Omit to list all session tasks."));
        return new JsonSchema("object", props, List.of(), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> {
            String sessionId = ctx.sessionId() != null ? ctx.sessionId() : "default";
            SubagentCoordinator coordinator = DelegateTaskTool.coordinator();

            String taskId = (String) args.get("task_id");
            if (taskId != null && !taskId.isBlank()) {
                SubagentCoordinator.TaskEntry entry = coordinator.getTask(taskId);
                if (entry == null) {
                    return ToolResult.error("check_task_status", "Task not found: " + taskId);
                }
                return formatTaskEntry(entry);
            }

            List<SubagentCoordinator.TaskEntry> tasks = coordinator.getSessionTasks(sessionId);
            if (tasks.isEmpty()) {
                return ToolResult.success("check_task_status", "No delegated tasks in this session.");
            }

            StringBuilder sb = new StringBuilder("Delegated tasks:\n\n");
            for (var task : tasks) {
                sb.append("- [").append(task.status()).append("] ")
                        .append(task.taskId()).append(": ").append(task.description());
                sb.append(" (").append(formatDuration(task.startedAt())).append(")\n");
                if (task.status() != SubagentCoordinator.TaskStatus.RUNNING && task.result() != null) {
                    String preview = task.result().length() > 100
                            ? task.result().substring(0, 100) + "..." : task.result();
                    sb.append("  Result: ").append(preview).append("\n");
                }
            }
            return ToolResult.success("check_task_status", sb.toString());
        });
    }

    private ToolResult formatTaskEntry(SubagentCoordinator.TaskEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(entry.taskId()).append("\n");
        sb.append("Status: ").append(entry.status()).append("\n");
        sb.append("Description: ").append(entry.description()).append("\n");
        sb.append("Started: ").append(formatDuration(entry.startedAt())).append(" ago\n");
        if (entry.result() != null) {
            sb.append("\nResult:\n").append(entry.result());
        }
        return ToolResult.success("check_task_status", sb.toString());
    }

    private String formatDuration(Instant start) {
        Duration d = Duration.between(start, Instant.now());
        if (d.toMinutes() > 0) {
            return d.toMinutes() + "m " + (d.toSeconds() % 60) + "s";
        }
        return d.toSeconds() + "s";
    }
}
