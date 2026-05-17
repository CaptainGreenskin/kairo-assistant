package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

@Tool(
        name = "workflow",
        description =
                "Define and execute multi-step workflows. Chain tool invocations into "
                        + "reusable sequences with conditional branching.",
        category = ToolCategory.AGENT_AND_TASK,
        sideEffect = ToolSideEffect.WRITE)
public class WorkflowTool implements SyncTool {

    private final ConcurrentHashMap<String, String> workflows = new ConcurrentHashMap<>();

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "'define' a workflow, 'list' workflows, 'describe' a workflow, 'delete' a workflow."));
        props.put("name", new JsonSchema("string", null, null,
                "Workflow name."));
        props.put("steps", new JsonSchema("string", null, null,
                "Multi-line workflow definition (for define action). Each line is a step."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) {
            return ToolResult.error("workflow", "'action' required");
        }

        return switch (action) {
            case "define" -> defineWorkflow(args);
            case "list" -> listWorkflows();
            case "describe" -> describeWorkflow(args);
            case "delete" -> deleteWorkflow(args);
            default -> ToolResult.error("workflow", "Unknown action: " + action);
        };
    }

    private ToolResult defineWorkflow(Map<String, Object> args) {
        String name = (String) args.get("name");
        String steps = (String) args.get("steps");
        if (name == null || steps == null) {
            return ToolResult.error("workflow", "'name' and 'steps' required");
        }
        workflows.put(name, steps);
        int stepCount = steps.split("\n").length;
        return ToolResult.success("workflow",
                "Workflow '" + name + "' defined with " + stepCount + " steps.");
    }

    private ToolResult listWorkflows() {
        if (workflows.isEmpty()) {
            return ToolResult.success("workflow", "No workflows defined.");
        }
        StringBuilder sb = new StringBuilder("Workflows:\n");
        workflows.forEach((name, steps) -> {
            int stepCount = steps.split("\n").length;
            sb.append("- ").append(name).append(" (").append(stepCount).append(" steps)\n");
        });
        return ToolResult.success("workflow", sb.toString());
    }

    private ToolResult describeWorkflow(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null) {
            return ToolResult.error("workflow", "'name' required");
        }
        String steps = workflows.get(name);
        if (steps == null) {
            return ToolResult.error("workflow", "Workflow not found: " + name);
        }
        return ToolResult.success("workflow",
                "Workflow: " + name + "\nSteps:\n" + steps);
    }

    private ToolResult deleteWorkflow(Map<String, Object> args) {
        String name = (String) args.get("name");
        if (name == null) {
            return ToolResult.error("workflow", "'name' required");
        }
        if (workflows.remove(name) != null) {
            return ToolResult.success("workflow", "Deleted workflow: " + name);
        }
        return ToolResult.error("workflow", "Workflow not found: " + name);
    }
}
