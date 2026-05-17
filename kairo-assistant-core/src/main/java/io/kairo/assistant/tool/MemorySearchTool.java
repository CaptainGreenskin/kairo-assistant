package io.kairo.assistant.tool;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryQuery;
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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

@Tool(
        name = "memory_search",
        description =
                "Search memory with rich filters: keyword, tags, time range, importance threshold, "
                        + "and scope. Returns matching memory entries sorted by relevance.",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class MemorySearchTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("keyword", new JsonSchema("string", null, null,
                "Search keyword."));
        props.put("tags", new JsonSchema("string", null, null,
                "Comma-separated tags to filter (AND logic)."));
        props.put("scope", new JsonSchema("string", null, null,
                "Scope: session, agent, global. Default: all."));
        props.put("time_range", new JsonSchema("string", null, null,
                "Time range: 1h, 6h, 1d, 7d, 30d, all. Default: all."));
        props.put("min_importance", new JsonSchema("number", null, null,
                "Minimum importance 0.0-1.0. Default 0.0."));
        props.put("limit", new JsonSchema("integer", null, null,
                "Max results. Default 20."));
        return new JsonSchema("object", props, List.of(), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        MemoryStore store = resolveMemoryStore(ctx);
        if (store == null) {
            return Mono.just(ToolResult.error("memory_search", "MemoryStore not available"));
        }

        String keyword = (String) args.get("keyword");
        String tagsStr = (String) args.get("tags");
        String scopeStr = (String) args.get("scope");
        String timeRange = (String) args.get("time_range");
        final double minImportance = args.get("min_importance") instanceof Number n
                ? n.doubleValue() : 0.0;
        final int limit = args.get("limit") instanceof Number n
                ? Math.max(1, Math.min(100, n.intValue())) : 20;

        Set<String> tags = Set.of();
        if (tagsStr != null && !tagsStr.isBlank()) {
            tags = Set.of(tagsStr.split(",")).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }

        Instant from = resolveTimeRange(timeRange);

        MemoryQuery.Builder qb = MemoryQuery.builder()
                .keyword(keyword)
                .tags(tags)
                .minImportance(minImportance)
                .from(from)
                .limit(limit);

        if (scopeStr != null && !scopeStr.isBlank()) {
            qb.namespace(scopeStr.toLowerCase());
        }

        MemoryQuery query = qb.build();

        if (scopeStr != null && !scopeStr.isBlank()) {
            MemoryScope scope;
            try {
                scope = MemoryScope.valueOf(scopeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Mono.just(ToolResult.error("memory_search",
                        "Invalid scope: " + scopeStr + ". Use session/agent/global."));
            }
            return store.search(keyword != null ? keyword : "", scope, List.copyOf(tags))
                    .filter(e -> e.importance() >= minImportance)
                    .filter(e -> from == null || (e.timestamp() != null && !e.timestamp().isBefore(from)))
                    .take(limit)
                    .map(this::formatEntry)
                    .collectList()
                    .map(results -> formatResults(results, keyword));
        }

        return store.search(query)
                .map(this::formatEntry)
                .collectList()
                .map(results -> formatResults(results, keyword));
    }

    private String formatEntry(MemoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(entry.id()).append("] ");
        sb.append("(").append(entry.scope()).append(") ");
        sb.append(entry.content());
        if (!entry.tags().isEmpty()) {
            sb.append(" #").append(String.join(" #", entry.tags()));
        }
        sb.append(" [importance=").append(String.format("%.1f", entry.importance())).append("]");
        return sb.toString();
    }

    private ToolResult formatResults(List<String> results, String keyword) {
        if (results.isEmpty()) {
            return ToolResult.success("memory_search",
                    "No memories found" + (keyword != null ? " for: " + keyword : "."));
        }
        return ToolResult.success("memory_search",
                "Found " + results.size() + " memories:\n" + String.join("\n", results),
                Map.of("count", results.size()));
    }

    private Instant resolveTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank() || "all".equalsIgnoreCase(timeRange)) {
            return null;
        }
        Instant now = Instant.now();
        return switch (timeRange.toLowerCase()) {
            case "1h" -> now.minus(1, ChronoUnit.HOURS);
            case "6h" -> now.minus(6, ChronoUnit.HOURS);
            case "1d" -> now.minus(1, ChronoUnit.DAYS);
            case "7d" -> now.minus(7, ChronoUnit.DAYS);
            case "30d" -> now.minus(30, ChronoUnit.DAYS);
            default -> null;
        };
    }

    private MemoryStore resolveMemoryStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object ms = ctx.dependencies().get("memoryStore");
        return ms instanceof MemoryStore store ? store : null;
    }
}
