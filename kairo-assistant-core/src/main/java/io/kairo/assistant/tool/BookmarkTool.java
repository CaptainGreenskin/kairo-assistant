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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;

@Tool(
        name = "bookmark",
        description =
                "Save and manage web bookmarks. Actions: save (add a bookmark), "
                        + "list (list all bookmarks), search (search by keyword/tag), "
                        + "delete (remove a bookmark).",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.WRITE)
public class BookmarkTool implements SyncTool {

    private static final String TAG = "bookmark";

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: save, list, search, delete."));
        props.put("url", new JsonSchema("string", null, null,
                "URL to bookmark (for save)."));
        props.put("title", new JsonSchema("string", null, null,
                "Title/description (for save)."));
        props.put("tags", new JsonSchema("string", null, null,
                "Comma-separated tags."));
        props.put("query", new JsonSchema("string", null, null,
                "Search keyword (for search)."));
        props.put("id", new JsonSchema("string", null, null,
                "Bookmark ID (for delete)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        String action = (String) args.get("action");
        if (action == null) {
            return Mono.just(ToolResult.error("bookmark", "'action' required"));
        }

        MemoryStore store = resolveMemoryStore(ctx);
        if (store == null) {
            return Mono.just(ToolResult.error("bookmark", "MemoryStore not available"));
        }

        return switch (action.toLowerCase()) {
            case "save" -> doSave(args, store);
            case "list" -> doList(store);
            case "search" -> doSearch(args, store);
            case "delete" -> doDelete(args, store);
            default -> Mono.just(ToolResult.error("bookmark",
                    "Unknown action: " + action));
        };
    }

    private Mono<ToolResult> doSave(Map<String, Object> args, MemoryStore store) {
        String url = (String) args.get("url");
        String rawTitle = (String) args.get("title");
        if (url == null || url.isBlank()) {
            return Mono.just(ToolResult.error("bookmark", "'url' required"));
        }
        String title = (rawTitle == null || rawTitle.isBlank()) ? url : rawTitle;

        Set<String> tags = new HashSet<>();
        tags.add(TAG);
        String tagsStr = (String) args.get("tags");
        if (tagsStr != null) {
            for (String t : tagsStr.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) tags.add(trimmed);
            }
        }

        String id = "bm:" + UUID.randomUUID().toString().substring(0, 8);
        String content = title + "\n" + url;
        MemoryEntry entry = new MemoryEntry(
                id, null, content, null,
                MemoryScope.GLOBAL, 0.6, null, tags,
                Instant.now(), Map.of("url", url, "title", title));

        return store.save(entry)
                .map(saved -> ToolResult.success("bookmark",
                        "Bookmarked: " + title + " → " + url + " (id=" + id + ")"));
    }

    private Mono<ToolResult> doList(MemoryStore store) {
        return store.list(MemoryScope.GLOBAL)
                .filter(e -> e.tags().contains(TAG))
                .map(e -> "[" + e.id() + "] " + e.content())
                .collectList()
                .map(items -> {
                    if (items.isEmpty()) {
                        return ToolResult.success("bookmark", "No bookmarks saved.");
                    }
                    return ToolResult.success("bookmark",
                            items.size() + " bookmarks:\n" + String.join("\n\n", items));
                });
    }

    private Mono<ToolResult> doSearch(Map<String, Object> args, MemoryStore store) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return Mono.just(ToolResult.error("bookmark", "'query' required for search"));
        }
        return store.search(query, MemoryScope.GLOBAL, List.of(TAG))
                .take(20)
                .map(e -> "[" + e.id() + "] " + e.content())
                .collectList()
                .map(items -> {
                    if (items.isEmpty()) {
                        return ToolResult.success("bookmark", "No bookmarks match: " + query);
                    }
                    return ToolResult.success("bookmark",
                            items.size() + " matches:\n" + String.join("\n\n", items));
                });
    }

    private Mono<ToolResult> doDelete(Map<String, Object> args, MemoryStore store) {
        String id = (String) args.get("id");
        if (id == null || id.isBlank()) {
            return Mono.just(ToolResult.error("bookmark", "'id' required for delete"));
        }
        return store.delete(id)
                .then(Mono.just(ToolResult.success("bookmark", "Deleted bookmark: " + id)));
    }

    private MemoryStore resolveMemoryStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object ms = ctx.dependencies().get("memoryStore");
        return ms instanceof MemoryStore store ? store : null;
    }
}
