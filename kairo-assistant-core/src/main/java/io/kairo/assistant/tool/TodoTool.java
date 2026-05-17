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
        name = "todo",
        description =
                "Manage a persistent todo list. Actions: add, list, complete, delete. "
                        + "Tasks persist across restarts.",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.WRITE)
public class TodoTool implements SyncTool {

    private static final String TAG = "todo";
    private static final double DEFAULT_IMPORTANCE = 0.7;

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: add, list, complete, or delete."));
        props.put("text", new JsonSchema("string", null, null,
                "Todo text (for add action)."));
        props.put("id", new JsonSchema("string", null, null,
                "Todo id (for complete/delete)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        String action = (String) args.getOrDefault("action", "list");

        MemoryStore store = resolveMemoryStore(ctx);
        if (store == null) {
            return Mono.just(ToolResult.error("todo", "MemoryStore not available"));
        }

        return switch (action) {
            case "add" -> doAdd(args, store);
            case "list" -> doList(store);
            case "complete" -> doComplete(args, store);
            case "delete" -> doDelete(args, store);
            default -> Mono.just(ToolResult.error("todo", "Unknown action: " + action));
        };
    }

    private Mono<ToolResult> doAdd(Map<String, Object> args, MemoryStore store) {
        String text = (String) args.get("text");
        if (text == null || text.isBlank()) {
            return Mono.just(ToolResult.error("todo", "Parameter 'text' is required for add"));
        }
        String id = "todo:" + UUID.randomUUID().toString().substring(0, 6);
        MemoryEntry entry = new MemoryEntry(
                id, null, text, null,
                MemoryScope.GLOBAL, DEFAULT_IMPORTANCE, null,
                Set.of(TAG), Instant.now(), Map.of("completed", "false"));

        return store.save(entry)
                .map(saved -> ToolResult.success("todo", "Added todo [" + id + "]: " + text));
    }

    private Mono<ToolResult> doList(MemoryStore store) {
        return store.list(MemoryScope.GLOBAL)
                .filter(e -> e.tags().contains(TAG))
                .collectList()
                .map(items -> {
                    if (items.isEmpty()) {
                        return ToolResult.success("todo", "No todos.");
                    }
                    StringBuilder sb = new StringBuilder(items.size() + " todo(s):\n");
                    for (MemoryEntry e : items) {
                        boolean done = "true".equals(e.metadata().get("completed"));
                        sb.append(done ? "[x] " : "[ ] ")
                                .append("[").append(e.id()).append("] ")
                                .append(e.content()).append('\n');
                    }
                    return ToolResult.success("todo", sb.toString().trim());
                });
    }

    private Mono<ToolResult> doComplete(Map<String, Object> args, MemoryStore store) {
        String id = (String) args.get("id");
        if (id == null) return Mono.just(ToolResult.error("todo", "'id' is required"));

        return store.get(id)
                .flatMap(entry -> {
                    Map<String, Object> meta = new LinkedHashMap<>(entry.metadata());
                    meta.put("completed", "true");
                    MemoryEntry updated = new MemoryEntry(
                            entry.id(), entry.agentId(), entry.content(), entry.rawContent(),
                            entry.scope(), entry.importance(), entry.embedding(), entry.tags(),
                            entry.timestamp(), meta);
                    return store.save(updated)
                            .map(saved -> ToolResult.success("todo", "Completed: " + entry.content()));
                })
                .defaultIfEmpty(ToolResult.error("todo", "Todo not found: " + id));
    }

    private Mono<ToolResult> doDelete(Map<String, Object> args, MemoryStore store) {
        String id = (String) args.get("id");
        if (id == null) return Mono.just(ToolResult.error("todo", "'id' is required"));

        return store.get(id)
                .flatMap(entry -> store.delete(id)
                        .then(Mono.just(ToolResult.success("todo", "Deleted todo: " + id))))
                .defaultIfEmpty(ToolResult.error("todo", "Todo not found: " + id));
    }

    private MemoryStore resolveMemoryStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object ms = ctx.dependencies().get("memoryStore");
        return ms instanceof MemoryStore store ? store : null;
    }
}
