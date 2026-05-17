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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;

@Tool(
        name = "note",
        description =
                "Save, search, list, or delete notes. Use action='save' to store, "
                        + "'search' to find, 'list' to show all, 'delete' to remove by id.",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.WRITE)
public class NoteTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put(
                "action",
                new JsonSchema(
                        "string",
                        null,
                        null,
                        "Action: save, search, list, or delete."));
        props.put(
                "content",
                new JsonSchema("string", null, null, "Note content (for save action)."));
        props.put(
                "query",
                new JsonSchema("string", null, null, "Search query (for search action)."));
        props.put(
                "tags",
                new JsonSchema("string", null, null, "Comma-separated tags (for save action)."));
        props.put(
                "id",
                new JsonSchema("string", null, null, "Note id (for delete action)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.defer(() -> {
            MemoryStore store = resolveStore(ctx);
            if (store == null) {
                return Mono.just(ToolResult.error("note", "MemoryStore not available"));
            }

            String action = (String) args.getOrDefault("action", "list");
            return switch (action) {
                case "save" -> doSave(args, store);
                case "search" -> doSearch(args, store);
                case "list" -> doList(store);
                case "delete" -> doDelete(args, store);
                default -> Mono.just(
                        ToolResult.error("note", "Unknown action: " + action));
            };
        });
    }

    private Mono<ToolResult> doSave(Map<String, Object> args, MemoryStore store) {
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            return Mono.just(ToolResult.error("note", "Parameter 'content' is required for save"));
        }
        String tagsStr = (String) args.getOrDefault("tags", "");
        Set<String> tags =
                tagsStr.isBlank()
                        ? Set.of("note")
                        : Set.of(tagsStr.split(","));

        String id = UUID.randomUUID().toString().substring(0, 8);
        MemoryEntry entry =
                new MemoryEntry(
                        id,
                        "assistant",
                        content,
                        null,
                        MemoryScope.GLOBAL,
                        0.7,
                        null,
                        tags,
                        java.time.Instant.now(),
                        null);

        return store.save(entry).map(saved -> ToolResult.success("note", "Saved note [" + id + "]: " + content));
    }

    private Mono<ToolResult> doSearch(Map<String, Object> args, MemoryStore store) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return Mono.just(ToolResult.error("note", "Parameter 'query' is required for search"));
        }
        return store
                .search(query, MemoryScope.GLOBAL)
                .collectList()
                .map(entries -> {
                    if (entries.isEmpty()) {
                        return ToolResult.success("note", "No notes found for: " + query);
                    }
                    StringBuilder sb = new StringBuilder("Found " + entries.size() + " note(s):\n");
                    for (MemoryEntry e : entries) {
                        sb.append("- [")
                                .append(e.id())
                                .append("] ")
                                .append(e.content())
                                .append(" (tags: ")
                                .append(e.tags())
                                .append(")\n");
                    }
                    return ToolResult.success("note", sb.toString().trim());
                });
    }

    private Mono<ToolResult> doList(MemoryStore store) {
        return store
                .list(MemoryScope.GLOBAL)
                .collectList()
                .map(entries -> {
                    if (entries.isEmpty()) {
                        return ToolResult.success("note", "No notes saved yet.");
                    }
                    StringBuilder sb = new StringBuilder(entries.size() + " note(s):\n");
                    for (MemoryEntry e : entries) {
                        sb.append("- [")
                                .append(e.id())
                                .append("] ")
                                .append(e.content())
                                .append("\n");
                    }
                    return ToolResult.success("note", sb.toString().trim());
                });
    }

    private Mono<ToolResult> doDelete(Map<String, Object> args, MemoryStore store) {
        String id = (String) args.get("id");
        if (id == null || id.isBlank()) {
            return Mono.just(ToolResult.error("note", "Parameter 'id' is required for delete"));
        }
        return store.delete(id).then(Mono.just(ToolResult.success("note", "Deleted note: " + id)));
    }

    private MemoryStore resolveStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object store = ctx.dependencies().get("memoryStore");
        return store instanceof MemoryStore ms ? ms : null;
    }
}
