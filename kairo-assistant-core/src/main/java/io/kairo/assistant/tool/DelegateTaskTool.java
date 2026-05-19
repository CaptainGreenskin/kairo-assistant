package io.kairo.assistant.tool;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.core.agent.AgentBuilder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "delegate_task",
        description =
                "Spawn an isolated sub-agent to handle a specific task. "
                        + "The sub-agent has its own context and returns a result. "
                        + "Use for tasks that can be done independently. "
                        + "Set async=true to run in background and get a task_id for later checking.",
        category = ToolCategory.AGENT_AND_TASK,
        timeoutSeconds = 300,
        sideEffect = ToolSideEffect.WRITE)
public class DelegateTaskTool implements SyncTool {

    private static final int DEFAULT_MAX_ITERATIONS = 15;
    private static final int MAX_ALLOWED_ITERATIONS = 50;
    private static final int DEFAULT_TOKEN_BUDGET = 64_000;
    private static final SubagentCoordinator COORDINATOR = new SubagentCoordinator();

    public static SubagentCoordinator coordinator() {
        return COORDINATOR;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("task", new JsonSchema("string", null, null, "Task description for the sub-agent."));
        props.put("context", new JsonSchema("string", null, null, "Optional context to pass to the sub-agent."));
        props.put("maxIterations", new JsonSchema("integer", null, null, "Max iterations for sub-agent. Default 15."));
        props.put("async", new JsonSchema("boolean", null, null,
                "If true, run task in background and return task_id immediately. Default false."));
        return new JsonSchema("object", props, List.of("task"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.defer(() -> {
            String task = (String) args.get("task");
            if (task == null || task.isBlank()) {
                return Mono.just(ToolResult.error("delegate_task", "Parameter 'task' is required"));
            }

            String context = (String) args.getOrDefault("context", "");
            boolean async = Boolean.TRUE.equals(args.get("async"));
            int maxIter = DEFAULT_MAX_ITERATIONS;
            if (args.get("maxIterations") instanceof Number n) {
                maxIter = Math.max(1, Math.min(MAX_ALLOWED_ITERATIONS, n.intValue()));
            }

            Object modelProvider = ctx.dependencies().get("modelProvider");
            String modelName = (String) ctx.dependencies().get("modelName");
            if (modelProvider == null || modelName == null) {
                return Mono.just(ToolResult.error("delegate_task",
                        "ModelProvider not available in dependencies — cannot spawn sub-agent"));
            }

            String prompt = context.isBlank() ? task : context + "\n\nTask: " + task;
            String sessionId = ctx.sessionId() != null ? ctx.sessionId() : "default";

            Agent subAgent;
            try {
                subAgent = AgentBuilder.create()
                        .name("delegate-" + System.currentTimeMillis())
                        .model((io.kairo.api.model.ModelProvider) modelProvider)
                        .modelName(modelName)
                        .systemPrompt("You are a focused sub-agent. Complete the given task concisely.")
                        .maxIterations(maxIter)
                        .timeout(Duration.ofMinutes(5))
                        .tokenBudget(DEFAULT_TOKEN_BUDGET)
                        .build();
            } catch (Exception e) {
                return Mono.just(ToolResult.error("delegate_task",
                        "Failed to create sub-agent: " + e.getMessage()));
            }

            if (async) {
                String taskId = COORDINATOR.submit(sessionId, task, subAgent, prompt);
                if (taskId == null) {
                    return Mono.just(ToolResult.error("delegate_task",
                            "Max concurrent subagents (3) reached. Wait for existing tasks to complete."));
                }
                return Mono.just(ToolResult.success("delegate_task",
                        "Task submitted (async). Task ID: " + taskId
                                + "\nUse check_task_status to monitor progress."));
            }

            return COORDINATOR.submitAndWait(sessionId, task, subAgent, prompt)
                    .map(response -> ToolResult.success("delegate_task",
                            "Sub-agent result:\n" + response.text()))
                    .onErrorResume(e -> Mono.just(ToolResult.error("delegate_task",
                            "Sub-agent failed: " + e.getMessage())));
        });
    }
}
