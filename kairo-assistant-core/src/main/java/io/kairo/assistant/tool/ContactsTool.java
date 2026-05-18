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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;

@Tool(
        name = "contacts",
        description =
                "Manage a personal contact list. Actions: add (name + optional email/phone/notes), "
                        + "search (by name or keyword), list (all contacts), update (modify fields by ID), "
                        + "delete (by ID).",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.WRITE)
public class ContactsTool implements SyncTool {

    private static final String TAG = "contact";
    private static final double DEFAULT_CONTACT_IMPORTANCE = 0.7;
    private static final int MAX_SEARCH_RESULTS = 20;

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: add, search, list, update, delete."));
        props.put("name", new JsonSchema("string", null, null, "Contact name."));
        props.put("email", new JsonSchema("string", null, null, "Email address."));
        props.put("phone", new JsonSchema("string", null, null, "Phone number."));
        props.put("notes", new JsonSchema("string", null, null, "Additional notes."));
        props.put("query", new JsonSchema("string", null, null, "Search keyword."));
        props.put("id", new JsonSchema("string", null, null, "Contact ID for update/delete."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        String action = (String) args.get("action");
        if (action == null) {
            return Mono.just(ToolResult.error("contacts", "'action' required"));
        }
        MemoryStore store = resolveMemoryStore(ctx);
        if (store == null) {
            return Mono.just(ToolResult.error("contacts", "MemoryStore not available"));
        }

        return switch (action.toLowerCase()) {
            case "add" -> doAdd(args, store);
            case "search" -> doSearch(args, store);
            case "list" -> doList(store);
            case "update" -> doUpdate(args, store);
            case "delete" -> doDelete(args, store);
            default -> Mono.just(ToolResult.error("contacts", "Unknown action: " + action));
        };
    }

    private Mono<ToolResult> doAdd(Map<String, Object> args, MemoryStore store) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) {
            return Mono.just(ToolResult.error("contacts", "'name' required for add"));
        }

        String email = (String) args.get("email");
        String phone = (String) args.get("phone");
        String notes = (String) args.get("notes");

        StringBuilder content = new StringBuilder(name);
        if (email != null) content.append("\nEmail: ").append(email);
        if (phone != null) content.append("\nPhone: ").append(phone);
        if (notes != null) content.append("\nNotes: ").append(notes);

        String id = "contact:" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", name);
        if (email != null) meta.put("email", email);
        if (phone != null) meta.put("phone", phone);

        MemoryEntry entry = new MemoryEntry(
                id, null, content.toString(), null,
                MemoryScope.GLOBAL, DEFAULT_CONTACT_IMPORTANCE, null,
                Set.of(TAG), Instant.now(), meta);

        return store.save(entry)
                .map(saved -> ToolResult.success("contacts",
                        "Added contact: " + name + " (id=" + id + ")"));
    }

    private Mono<ToolResult> doSearch(Map<String, Object> args, MemoryStore store) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return Mono.just(ToolResult.error("contacts", "'query' required for search"));
        }
        return store.search(query, MemoryScope.GLOBAL, List.of(TAG))
                .take(MAX_SEARCH_RESULTS)
                .map(e -> "[" + e.id() + "] " + e.content())
                .collectList()
                .map(items -> {
                    if (items.isEmpty()) {
                        return ToolResult.success("contacts", "No contacts match: " + query);
                    }
                    return ToolResult.success("contacts",
                            items.size() + " contacts found:\n" + String.join("\n\n", items));
                });
    }

    private Mono<ToolResult> doList(MemoryStore store) {
        return store.list(MemoryScope.GLOBAL)
                .filter(e -> e.tags().contains(TAG))
                .map(e -> "[" + e.id() + "] " + e.content())
                .collectList()
                .map(items -> {
                    if (items.isEmpty()) {
                        return ToolResult.success("contacts", "No contacts saved.");
                    }
                    return ToolResult.success("contacts",
                            items.size() + " contacts:\n" + String.join("\n\n", items));
                });
    }

    private Mono<ToolResult> doUpdate(Map<String, Object> args, MemoryStore store) {
        String id = (String) args.get("id");
        if (id == null || id.isBlank()) {
            return Mono.just(ToolResult.error("contacts", "'id' required for update"));
        }

        return store.get(id)
                .flatMap(entry -> {
                    Map<String, Object> meta = new HashMap<>(entry.metadata());
                    String name = args.get("name") instanceof String s ? s : (String) meta.get("name");
                    if (args.get("email") instanceof String s) meta.put("email", s);
                    if (args.get("phone") instanceof String s) meta.put("phone", s);
                    if (args.get("name") instanceof String s) meta.put("name", s);

                    StringBuilder content = new StringBuilder(name);
                    Object email = meta.get("email");
                    Object phone = meta.get("phone");
                    if (email != null) content.append("\nEmail: ").append(email);
                    if (phone != null) content.append("\nPhone: ").append(phone);
                    String notes = args.get("notes") instanceof String s ? s : null;
                    if (notes != null) content.append("\nNotes: ").append(notes);

                    MemoryEntry updated = new MemoryEntry(
                            entry.id(), entry.agentId(), content.toString(), entry.rawContent(),
                            entry.scope(), entry.importance(), entry.embedding(), entry.tags(),
                            entry.timestamp(), meta);

                    return store.save(updated)
                            .map(saved -> ToolResult.success("contacts",
                                    "Updated contact: " + name + " (id=" + id + ")"));
                })
                .defaultIfEmpty(ToolResult.error("contacts", "Contact not found: " + id));
    }

    private Mono<ToolResult> doDelete(Map<String, Object> args, MemoryStore store) {
        String id = (String) args.get("id");
        if (id == null || id.isBlank()) {
            return Mono.just(ToolResult.error("contacts", "'id' required for delete"));
        }
        return store.delete(id)
                .then(Mono.just(ToolResult.success("contacts", "Deleted contact: " + id)));
    }

    private MemoryStore resolveMemoryStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object ms = ctx.dependencies().get("memoryStore");
        return ms instanceof MemoryStore store ? store : null;
    }
}
