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
import reactor.core.publisher.Mono;

@Tool(
        name = "user_profile",
        description =
                "Manage user profile and preferences. Actions: get (retrieve profile), "
                        + "set (update a preference key-value), list (list all preferences), "
                        + "delete (remove a preference).",
        category = ToolCategory.AGENT_AND_TASK,
        sideEffect = ToolSideEffect.WRITE)
public class UserProfileTool implements SyncTool {

    private static final String PROFILE_ID_PREFIX = "profile:";

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: get, set, list, delete"));
        props.put("key", new JsonSchema("string", null, null,
                "Preference key (e.g. 'name', 'language', 'timezone')."));
        props.put("value", new JsonSchema("string", null, null,
                "Preference value (for 'set' action)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        String action = (String) args.get("action");
        if (action == null || action.isBlank()) {
            return Mono.just(ToolResult.error("user_profile", "'action' required"));
        }

        MemoryStore store = resolveMemoryStore(ctx);
        if (store == null) {
            return Mono.just(ToolResult.error("user_profile", "MemoryStore not available"));
        }

        return switch (action.toLowerCase()) {
            case "get" -> doGet(args, store);
            case "set" -> doSet(args, store);
            case "list" -> doList(store);
            case "delete" -> doDelete(args, store);
            default -> Mono.just(ToolResult.error("user_profile",
                    "Unknown action: " + action + ". Use get/set/list/delete."));
        };
    }

    private Mono<ToolResult> doGet(Map<String, Object> args, MemoryStore store) {
        String key = (String) args.get("key");
        if (key == null || key.isBlank()) {
            return Mono.just(ToolResult.error("user_profile", "'key' required for get"));
        }
        return store.get(PROFILE_ID_PREFIX + key)
                .map(entry -> ToolResult.success("user_profile",
                        key + " = " + entry.content()))
                .defaultIfEmpty(ToolResult.success("user_profile",
                        "No preference found for key: " + key));
    }

    private Mono<ToolResult> doSet(Map<String, Object> args, MemoryStore store) {
        String key = (String) args.get("key");
        String value = (String) args.get("value");
        if (key == null || key.isBlank()) {
            return Mono.just(ToolResult.error("user_profile", "'key' required for set"));
        }
        if (value == null) {
            return Mono.just(ToolResult.error("user_profile", "'value' required for set"));
        }
        MemoryEntry entry = new MemoryEntry(
                PROFILE_ID_PREFIX + key, null, value, null,
                MemoryScope.GLOBAL, 0.9, null,
                Set.of("profile", "preference"), Instant.now(),
                Map.of("profileKey", key));
        return store.save(entry)
                .map(saved -> ToolResult.success("user_profile",
                        "Saved preference: " + key + " = " + value));
    }

    private Mono<ToolResult> doList(MemoryStore store) {
        return store.list(MemoryScope.GLOBAL)
                .filter(e -> e.tags().contains("profile"))
                .map(e -> {
                    Object k = e.metadata().get("profileKey");
                    return (k != null ? k : e.id()) + " = " + e.content();
                })
                .collectList()
                .map(items -> {
                    if (items.isEmpty()) {
                        return ToolResult.success("user_profile", "No preferences set.");
                    }
                    return ToolResult.success("user_profile",
                            "User preferences:\n" + String.join("\n", items));
                });
    }

    private Mono<ToolResult> doDelete(Map<String, Object> args, MemoryStore store) {
        String key = (String) args.get("key");
        if (key == null || key.isBlank()) {
            return Mono.just(ToolResult.error("user_profile", "'key' required for delete"));
        }
        return store.delete(PROFILE_ID_PREFIX + key)
                .then(Mono.just(ToolResult.success("user_profile",
                        "Deleted preference: " + key)));
    }

    private MemoryStore resolveMemoryStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object ms = ctx.dependencies().get("memoryStore");
        return ms instanceof MemoryStore store ? store : null;
    }
}
