package io.kairo.assistant.tool;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;

@Tool(
        name = "checkpoint",
        description =
                "Create and manage conversation checkpoints. Save the current state, "
                        + "list checkpoints, or restore to a previous checkpoint.",
        category = ToolCategory.AGENT_AND_TASK,
        sideEffect = ToolSideEffect.WRITE)
public class CheckpointTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "'save' to create checkpoint, 'list' to show checkpoints, 'info' for checkpoint details, 'restore' to resume from a checkpoint."));
        props.put("label", new JsonSchema("string", null, null,
                "Label for the checkpoint (for save action)."));
        props.put("notes", new JsonSchema("string", null, null,
                "Optional notes about what was accomplished before this checkpoint."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args, ctx));
    }

    private ToolResult doExecute(Map<String, Object> args, ToolContext ctx) {
        String action = (String) args.get("action");
        if (action == null) {
            return ToolResult.error("checkpoint", "'action' required");
        }

        MemoryStore store = resolveMemoryStore(ctx);
        if (store == null) {
            return ToolResult.error("checkpoint", "MemoryStore not available");
        }

        return switch (action) {
            case "save" -> saveCheckpoint(args, store, ctx);
            case "list" -> listCheckpoints(store);
            case "info" -> checkpointInfo(args, store);
            case "restore" -> restoreCheckpoint(args, store);
            default -> ToolResult.error("checkpoint", "Unknown action: " + action);
        };
    }

    private ToolResult saveCheckpoint(Map<String, Object> args, MemoryStore store, ToolContext ctx) {
        String label = args.get("label") instanceof String l ? l : "checkpoint-" + Instant.now().getEpochSecond();
        String notes = args.get("notes") instanceof String n ? n : "";

        String content = String.format(
                "CHECKPOINT:%s\nTimestamp: %s\nSession: %s\nNotes: %s",
                label, Instant.now(), ctx.sessionId(), notes);

        MemoryEntry entry = MemoryEntry.session(
                UUID.randomUUID().toString(),
                content,
                Set.of("checkpoint", label));
        store.save(entry);

        return ToolResult.success("checkpoint",
                "Checkpoint saved: " + label + " at " + Instant.now());
    }

    private ToolResult listCheckpoints(MemoryStore store) {
        List<MemoryEntry> checkpoints = store.search("CHECKPOINT:",
                MemoryScope.SESSION, List.of("checkpoint")).collectList().block();
        if (checkpoints == null || checkpoints.isEmpty()) {
            return ToolResult.success("checkpoint", "No checkpoints found.");
        }

        StringBuilder sb = new StringBuilder("Checkpoints:\n");
        for (MemoryEntry entry : checkpoints) {
            String[] lines = entry.content().split("\n");
            String cpLabel = lines[0].replace("CHECKPOINT:", "");
            String timestamp = lines.length > 1 ? lines[1].replace("Timestamp: ", "") : "?";
            sb.append("- ").append(cpLabel).append(" (").append(timestamp).append(")\n");
        }
        return ToolResult.success("checkpoint", sb.toString());
    }

    private ToolResult checkpointInfo(Map<String, Object> args, MemoryStore store) {
        String label = (String) args.get("label");
        if (label == null) {
            return ToolResult.error("checkpoint", "'label' required for info action");
        }

        List<MemoryEntry> entries = store.search("CHECKPOINT:" + label,
                MemoryScope.SESSION, List.of("checkpoint")).collectList().block();
        if (entries == null || entries.isEmpty()) {
            return ToolResult.error("checkpoint", "Checkpoint not found: " + label);
        }

        return ToolResult.success("checkpoint", entries.get(0).content());
    }

    private ToolResult restoreCheckpoint(Map<String, Object> args, MemoryStore store) {
        String label = (String) args.get("label");
        if (label == null) {
            return ToolResult.error("checkpoint", "'label' required for restore action");
        }

        List<MemoryEntry> entries = store.search("CHECKPOINT:" + label,
                MemoryScope.SESSION, List.of("checkpoint")).collectList().block();
        if (entries == null || entries.isEmpty()) {
            return ToolResult.error("checkpoint", "Checkpoint not found: " + label);
        }

        String content = entries.get(0).content();
        return ToolResult.success("checkpoint",
                "Restored context from checkpoint '" + label + "'.\n"
                        + "Resume from this state:\n\n" + content
                        + "\n\nPlease continue from where this checkpoint was saved.");
    }

    private MemoryStore resolveMemoryStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object ms = ctx.dependencies().get("memoryStore");
        return ms instanceof MemoryStore store ? store : null;
    }
}
