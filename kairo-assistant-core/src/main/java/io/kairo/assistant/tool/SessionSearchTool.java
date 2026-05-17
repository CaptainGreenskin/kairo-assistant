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
import reactor.core.publisher.Mono;

@Tool(
        name = "session_search",
        description =
                "Search past conversation history and notes for relevant information. "
                        + "Uses text matching across stored memories.",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class SessionSearchTool implements SyncTool {

    private static final int DEFAULT_SEARCH_LIMIT = 10;
    private static final int MAX_SEARCH_LIMIT = 50;

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("query", new JsonSchema("string", null, null, "Search query."));
        props.put("scope", new JsonSchema("string", null, null,
                "Search scope: 'session', 'agent', or 'global'. Default 'global'."));
        props.put("limit", new JsonSchema("integer", null, null, "Max results. Default 10."));
        return new JsonSchema("object", props, List.of("query"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.defer(() -> {
            MemoryStore store = resolveStore(ctx);
            if (store == null) {
                return Mono.just(ToolResult.error("session_search", "MemoryStore not available"));
            }

            String query = (String) args.get("query");
            if (query == null || query.isBlank()) {
                return Mono.just(ToolResult.error("session_search", "Parameter 'query' is required"));
            }

            String scopeStr = (String) args.getOrDefault("scope", "global");
            MemoryScope scope = switch (scopeStr.toLowerCase()) {
                case "session" -> MemoryScope.SESSION;
                case "agent" -> MemoryScope.AGENT;
                default -> MemoryScope.GLOBAL;
            };

            int limit = DEFAULT_SEARCH_LIMIT;
            if (args.get("limit") instanceof Number n) {
                limit = Math.max(1, Math.min(MAX_SEARCH_LIMIT, n.intValue()));
            }

            int finalLimit = limit;
            return store.search(query, scope)
                    .take(finalLimit)
                    .collectList()
                    .map(entries -> {
                        if (entries.isEmpty()) {
                            return ToolResult.success("session_search",
                                    "No results found for: " + query);
                        }
                        StringBuilder sb = new StringBuilder("Found " + entries.size() + " result(s):\n\n");
                        for (int i = 0; i < entries.size(); i++) {
                            MemoryEntry e = entries.get(i);
                            sb.append(i + 1).append(". [").append(e.id()).append("] ")
                                    .append(e.timestamp()).append('\n')
                                    .append("   ").append(e.content()).append('\n');
                            if (!e.tags().isEmpty()) {
                                sb.append("   tags: ").append(e.tags()).append('\n');
                            }
                            sb.append('\n');
                        }
                        return ToolResult.success("session_search", sb.toString().trim());
                    });
        });
    }

    private MemoryStore resolveStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object store = ctx.dependencies().get("memoryStore");
        return store instanceof MemoryStore ms ? ms : null;
    }
}
