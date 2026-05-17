package io.kairo.assistant.tool;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

@Tool(
        name = "todo",
        description =
                "Manage a todo list. Actions: add, list, complete, delete. "
                        + "Tasks persist in memory during the session.",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.WRITE)
public class TodoTool implements SyncTool {

    record TodoItem(String id, String text, boolean completed, Instant createdAt) {}

    private final CopyOnWriteArrayList<TodoItem> items = new CopyOnWriteArrayList<>();

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null, "Action: add, list, complete, or delete."));
        props.put("text", new JsonSchema("string", null, null, "Todo text (for add action)."));
        props.put("id", new JsonSchema("string", null, null, "Todo id (for complete/delete)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> {
            String action = (String) args.getOrDefault("action", "list");
            return switch (action) {
                case "add" -> doAdd(args);
                case "list" -> doList();
                case "complete" -> doComplete(args);
                case "delete" -> doDelete(args);
                default -> ToolResult.error("todo", "Unknown action: " + action);
            };
        });
    }

    private ToolResult doAdd(Map<String, Object> args) {
        String text = (String) args.get("text");
        if (text == null || text.isBlank()) {
            return ToolResult.error("todo", "Parameter 'text' is required for add");
        }
        String id = UUID.randomUUID().toString().substring(0, 6);
        items.add(new TodoItem(id, text, false, Instant.now()));
        return ToolResult.success("todo", "Added todo [" + id + "]: " + text);
    }

    private ToolResult doList() {
        if (items.isEmpty()) {
            return ToolResult.success("todo", "No todos.");
        }
        StringBuilder sb = new StringBuilder(items.size() + " todo(s):\n");
        for (TodoItem item : items) {
            sb.append(item.completed ? "[x] " : "[ ] ")
                    .append("[").append(item.id).append("] ")
                    .append(item.text).append('\n');
        }
        return ToolResult.success("todo", sb.toString().trim());
    }

    private ToolResult doComplete(Map<String, Object> args) {
        String id = (String) args.get("id");
        if (id == null) return ToolResult.error("todo", "'id' is required");
        synchronized (items) {
            for (int i = 0; i < items.size(); i++) {
                TodoItem old = items.get(i);
                if (old.id.equals(id)) {
                    items.set(i, new TodoItem(old.id, old.text, true, old.createdAt));
                    return ToolResult.success("todo", "Completed: " + old.text);
                }
            }
        }
        return ToolResult.error("todo", "Todo not found: " + id);
    }

    private ToolResult doDelete(Map<String, Object> args) {
        String id = (String) args.get("id");
        if (id == null) return ToolResult.error("todo", "'id' is required");
        boolean removed = items.removeIf(item -> item.id.equals(id));
        return removed
                ? ToolResult.success("todo", "Deleted todo: " + id)
                : ToolResult.error("todo", "Todo not found: " + id);
    }
}
