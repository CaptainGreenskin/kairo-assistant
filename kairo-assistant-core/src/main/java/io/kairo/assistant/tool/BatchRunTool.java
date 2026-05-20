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
import reactor.core.publisher.Mono;

@Tool(
        name = "batch_run",
        description =
                "Execute a batch of prompts sequentially or in parallel. "
                        + "Actions: create (define batch), status (check progress), cancel (abort).",
        category = ToolCategory.SCHEDULING,
        sideEffect = ToolSideEffect.WRITE)
public class BatchRunTool implements SyncTool {

    private static volatile BatchRunner sharedRunner;

    public static void setRunner(BatchRunner runner) {
        sharedRunner = runner;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: create, status, cancel."));
        props.put("prompts", new JsonSchema("array", null, null,
                "List of prompts to execute (for create)."));
        props.put("parallel", new JsonSchema("boolean", null, null,
                "Execute in parallel (default: false = sequential)."));
        props.put("batch_id", new JsonSchema("string", null, null,
                "Batch ID (for status/cancel)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> {
            String action = (String) args.get("action");
            if (action == null || action.isBlank()) {
                return ToolResult.error("batch_run", "'action' required");
            }

            BatchRunner runner = sharedRunner;
            if (runner == null) {
                return ToolResult.error("batch_run", "Batch runner not initialized");
            }

            return switch (action) {
                case "create" -> {
                    Object promptsRaw = args.get("prompts");
                    if (!(promptsRaw instanceof List<?> list) || list.isEmpty()) {
                        yield ToolResult.error("batch_run", "'prompts' array required for create");
                    }
                    List<String> prompts = ((List<Object>) list).stream()
                            .map(Object::toString).toList();
                    boolean parallel = Boolean.TRUE.equals(args.get("parallel"));
                    String batchId = runner.submit(prompts, parallel, ctx.sessionId());
                    yield ToolResult.success("batch_run",
                            "Batch created: " + batchId + " (" + prompts.size() + " tasks, "
                                    + (parallel ? "parallel" : "sequential") + ")");
                }
                case "status" -> {
                    String id = (String) args.get("batch_id");
                    if (id == null || id.isBlank()) {
                        yield ToolResult.success("batch_run", runner.allStatus());
                    }
                    yield ToolResult.success("batch_run", runner.status(id));
                }
                case "cancel" -> {
                    String id = (String) args.get("batch_id");
                    if (id == null || id.isBlank()) {
                        yield ToolResult.error("batch_run", "'batch_id' required for cancel");
                    }
                    boolean cancelled = runner.cancel(id);
                    yield cancelled
                            ? ToolResult.success("batch_run", "Batch cancelled: " + id)
                            : ToolResult.error("batch_run", "Batch not found: " + id);
                }
                default -> ToolResult.error("batch_run", "Unknown action: " + action);
            };
        });
    }

    public interface BatchRunner {
        String submit(List<String> prompts, boolean parallel, String sessionId);
        String status(String batchId);
        String allStatus();
        boolean cancel(String batchId);
    }
}
