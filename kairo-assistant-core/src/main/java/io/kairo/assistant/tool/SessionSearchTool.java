package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.assistant.agent.ConversationStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "session_search",
        description =
                "Search past conversation sessions for relevant information. "
                        + "Finds messages containing the query and returns snippets with context. "
                        + "Use this to recall previous discussions, decisions, or information shared in past conversations.",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class SessionSearchTool implements SyncTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("query", new JsonSchema("string", null, null,
                "Search query — keywords or phrases to find in conversation history."));
        props.put("limit", new JsonSchema("integer", null, null,
                "Max number of sessions to return. Default 5, max 20."));
        return new JsonSchema("object", props, List.of("query"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.defer(() -> {
            ConversationStore store = resolveStore(ctx);
            if (store == null) {
                return Mono.just(ToolResult.error("session_search",
                        "ConversationStore not available"));
            }

            String query = (String) args.get("query");
            if (query == null || query.isBlank()) {
                return Mono.just(ToolResult.error("session_search",
                        "Parameter 'query' is required"));
            }

            int limit = DEFAULT_LIMIT;
            if (args.get("limit") instanceof Number n) {
                limit = Math.max(1, Math.min(MAX_LIMIT, n.intValue()));
            }

            List<Map<String, Object>> grouped = store.searchGrouped(query, limit);
            if (grouped.isEmpty()) {
                return Mono.just(ToolResult.success("session_search",
                        "No conversations found matching: " + query));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found matches in ").append(grouped.size()).append(" session(s):\n\n");

            for (int i = 0; i < grouped.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> session = grouped.get(i);
                sb.append("--- Session ").append(i + 1).append(" ---\n");
                sb.append("ID: ").append(session.get("sessionId"));
                if (session.containsKey("title")) {
                    sb.append(" | Title: ").append(session.get("title"));
                }
                sb.append("\nMatches: ").append(session.get("matchCount")).append("\n");

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> matches =
                        (List<Map<String, Object>>) session.get("matches");
                for (var match : matches) {
                    sb.append("  [").append(match.get("role")).append("] ")
                            .append(match.get("snippet")).append("\n");
                }
                sb.append("\n");
            }

            return Mono.just(ToolResult.success("session_search", sb.toString().trim()));
        });
    }

    private ConversationStore resolveStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object store = ctx.dependencies().get("conversationStore");
        return store instanceof ConversationStore cs ? cs : null;
    }
}
