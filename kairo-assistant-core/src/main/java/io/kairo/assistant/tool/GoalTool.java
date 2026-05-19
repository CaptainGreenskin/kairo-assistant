package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.assistant.goal.Goal;
import io.kairo.assistant.goal.GoalStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

@Tool(
        name = "goal",
        description =
                "Manage persistent goals that execute on a cron schedule. "
                        + "Actions: create (new goal), list (show all), cancel (remove goal), "
                        + "pause (stop firing), resume (re-activate), trigger (fire immediately).",
        category = ToolCategory.SCHEDULING,
        sideEffect = ToolSideEffect.WRITE)
public class GoalTool implements SyncTool {

    private static volatile GoalStore sharedStore;

    public static void setStore(GoalStore store) {
        sharedStore = store;
    }

    public static GoalStore store() {
        return sharedStore;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: create, list, cancel, pause, resume, trigger."));
        props.put("description", new JsonSchema("string", null, null,
                "Goal description (for create)."));
        props.put("prompt", new JsonSchema("string", null, null,
                "Prompt to execute when goal fires (for create)."));
        props.put("cron", new JsonSchema("string", null, null,
                "5-field cron expression: min hour dom month dow (for create)."));
        props.put("channel", new JsonSchema("string", null, null,
                "Target channel for results: dingtalk, feishu, etc. (for create)."));
        props.put("target", new JsonSchema("string", null, null,
                "Target conversation/user ID for results (for create)."));
        props.put("goal_id", new JsonSchema("string", null, null,
                "Goal ID (for cancel/pause/resume/trigger)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null || action.isBlank()) {
            return ToolResult.error("goal", "'action' required");
        }

        GoalStore store = sharedStore;
        if (store == null) {
            return ToolResult.error("goal", "Goal system not initialized");
        }

        return switch (action) {
            case "create" -> createGoal(args, store);
            case "list" -> listGoals(store);
            case "cancel" -> cancelGoal(args, store);
            case "pause" -> updateStatus(args, store, Goal.GoalStatus.PAUSED);
            case "resume" -> updateStatus(args, store, Goal.GoalStatus.ACTIVE);
            case "trigger" -> triggerGoal(args, store);
            default -> ToolResult.error("goal", "Unknown action: " + action);
        };
    }

    private ToolResult createGoal(Map<String, Object> args, GoalStore store) {
        String description = (String) args.get("description");
        String prompt = (String) args.get("prompt");
        String cron = (String) args.get("cron");

        if (description == null || description.isBlank()) {
            return ToolResult.error("goal", "'description' required for create");
        }
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.error("goal", "'prompt' required for create");
        }
        if (cron == null || cron.isBlank()) {
            return ToolResult.error("goal", "'cron' required for create");
        }

        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) {
            return ToolResult.error("goal",
                    "Invalid cron: expected 5 fields (min hour dom month dow), got " + parts.length);
        }

        String channel = (String) args.getOrDefault("channel", "");
        String target = (String) args.getOrDefault("target", "");

        String id = UUID.randomUUID().toString().substring(0, 8);
        Goal goal = new Goal(id, description, prompt, cron, channel, target,
                Instant.now(), null, Goal.GoalStatus.ACTIVE);
        store.save(goal);

        return ToolResult.success("goal",
                "Goal created!\nID: " + id
                        + "\nDescription: " + description
                        + "\nSchedule: " + cron
                        + "\nPrompt: " + prompt);
    }

    private ToolResult listGoals(GoalStore store) {
        List<Goal> goals = store.all();
        if (goals.isEmpty()) {
            return ToolResult.success("goal", "No goals configured.");
        }

        StringBuilder sb = new StringBuilder("Goals:\n\n");
        for (Goal g : goals) {
            sb.append("[").append(g.status()).append("] ")
                    .append(g.id()).append(": ").append(g.description()).append("\n");
            sb.append("  Schedule: ").append(g.cron()).append("\n");
            sb.append("  Prompt: ").append(g.prompt().length() > 60
                    ? g.prompt().substring(0, 60) + "..." : g.prompt()).append("\n");
            if (g.lastRunAt() != null) {
                sb.append("  Last run: ").append(g.lastRunAt()).append("\n");
            }
            if (g.channel() != null && !g.channel().isBlank()) {
                sb.append("  Notify: ").append(g.channel())
                        .append("/").append(g.target()).append("\n");
            }
            sb.append("\n");
        }
        return ToolResult.success("goal", sb.toString());
    }

    private ToolResult cancelGoal(Map<String, Object> args, GoalStore store) {
        String id = (String) args.get("goal_id");
        if (id == null || id.isBlank()) {
            return ToolResult.error("goal", "'goal_id' required for cancel");
        }
        boolean removed = store.delete(id);
        return removed
                ? ToolResult.success("goal", "Goal cancelled: " + id)
                : ToolResult.error("goal", "Goal not found: " + id);
    }

    private ToolResult updateStatus(Map<String, Object> args, GoalStore store,
                                    Goal.GoalStatus newStatus) {
        String id = (String) args.get("goal_id");
        if (id == null || id.isBlank()) {
            return ToolResult.error("goal", "'goal_id' required");
        }
        return store.get(id).map(goal -> {
            store.update(goal.withStatus(newStatus));
            return ToolResult.success("goal",
                    "Goal " + id + " status changed to " + newStatus);
        }).orElse(ToolResult.error("goal", "Goal not found: " + id));
    }

    private ToolResult triggerGoal(Map<String, Object> args, GoalStore store) {
        String id = (String) args.get("goal_id");
        if (id == null || id.isBlank()) {
            return ToolResult.error("goal", "'goal_id' required for trigger");
        }
        return store.get(id).map(goal -> {
            store.update(goal.withLastRun(Instant.now()));
            return ToolResult.success("goal",
                    "Goal triggered: " + id + "\nPrompt: " + goal.prompt()
                            + "\n\n(The goal prompt should be executed by the agent)");
        }).orElse(ToolResult.error("goal", "Goal not found: " + id));
    }
}
