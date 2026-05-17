package io.kairo.assistant.tool;

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
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

@Tool(
        name = "env",
        description =
                "Read environment variables. Actions: get (single var), list (filtered list), "
                        + "search (search by pattern in names/values).",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class EnvTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: get, list, search. Default: list."));
        props.put("name", new JsonSchema("string", null, null,
                "Variable name (for get)."));
        props.put("filter", new JsonSchema("string", null, null,
                "Filter pattern for list/search (substring match on name)."));
        return new JsonSchema("object", props, List.of(), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.getOrDefault("action", "list");

        return switch (action.toLowerCase()) {
            case "get" -> {
                String name = (String) args.get("name");
                if (name == null) yield ToolResult.error("env", "'name' required for get");
                String value = System.getenv(name);
                if (value == null) {
                    yield ToolResult.success("env", name + " is not set");
                }
                yield ToolResult.success("env", name + "=" + maskSensitive(name, value));
            }
            case "list", "search" -> {
                String filter = (String) args.get("filter");
                String filterLower = filter != null ? filter.toLowerCase() : null;
                List<String> entries = System.getenv().entrySet().stream()
                        .filter(e -> filterLower == null
                                || e.getKey().toLowerCase().contains(filterLower))
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + "=" + maskSensitive(e.getKey(), e.getValue()))
                        .collect(Collectors.toList());

                if (entries.isEmpty()) {
                    yield ToolResult.success("env",
                            "No variables" + (filter != null ? " matching: " + filter : ""));
                }
                yield ToolResult.success("env",
                        entries.size() + " variables:\n" + String.join("\n", entries));
            }
            default -> ToolResult.error("env", "Unknown action: " + action);
        };
    }

    private String maskSensitive(String name, String value) {
        String upper = name.toUpperCase();
        if (upper.contains("KEY") || upper.contains("SECRET") || upper.contains("TOKEN")
                || upper.contains("PASSWORD") || upper.contains("CREDENTIAL")) {
            if (value.length() <= 8) return "****";
            return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
        }
        return value;
    }
}
