package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

@Tool(
        name = "text",
        description =
                "Text manipulation utilities. Actions: count (words/lines/chars), "
                        + "replace (find & replace), regex (regex match/extract), "
                        + "case (upper/lower/title), trim, reverse, sort_lines, unique_lines.",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.READ_ONLY)
public class TextTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: count, replace, regex, upper, lower, title, trim, reverse, sort_lines, unique_lines."));
        props.put("text", new JsonSchema("string", null, null, "Input text."));
        props.put("find", new JsonSchema("string", null, null,
                "Text/pattern to find (for replace/regex)."));
        props.put("replacement", new JsonSchema("string", null, null,
                "Replacement text (for replace)."));
        return new JsonSchema("object", props, List.of("action", "text"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        String text = (String) args.get("text");
        if (text == null) {
            return ToolResult.error("text", "'text' required");
        }

        return switch (action.toLowerCase()) {
            case "count" -> {
                int chars = text.length();
                int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
                long lines = text.lines().count();
                yield ToolResult.success("text",
                        "Characters: " + chars + ", Words: " + words + ", Lines: " + lines);
            }
            case "replace" -> {
                String find = (String) args.get("find");
                String replacement = (String) args.get("replacement");
                if (find == null) yield ToolResult.error("text", "'find' required");
                else yield ToolResult.success("text",
                        text.replace(find, replacement != null ? replacement : ""));
            }
            case "regex" -> {
                String pattern = (String) args.get("find");
                if (pattern == null) yield ToolResult.error("text", "'find' (regex) required");
                else {
                    Matcher m = Pattern.compile(pattern).matcher(text);
                    StringBuilder sb = new StringBuilder();
                    int count = 0;
                    while (m.find()) {
                        sb.append("Match ").append(++count).append(": ").append(m.group()).append("\n");
                    }
                    yield ToolResult.success("text",
                            count == 0 ? "No matches" : count + " matches:\n" + sb.toString().trim());
                }
            }
            case "upper" -> ToolResult.success("text", text.toUpperCase());
            case "lower" -> ToolResult.success("text", text.toLowerCase());
            case "title" -> ToolResult.success("text", toTitleCase(text));
            case "trim" -> ToolResult.success("text", text.strip());
            case "reverse" -> ToolResult.success("text", new StringBuilder(text).reverse().toString());
            case "sort_lines" -> ToolResult.success("text",
                    text.lines().sorted().collect(Collectors.joining("\n")));
            case "unique_lines" -> ToolResult.success("text",
                    text.lines().distinct().collect(Collectors.joining("\n")));
            default -> ToolResult.error("text", "Unknown action: " + action);
        };
    }

    private String toTitleCase(String text) {
        return Arrays.stream(text.split("\\b"))
                .map(word -> {
                    if (word.isEmpty()) return word;
                    return Character.toUpperCase(word.charAt(0))
                            + word.substring(1).toLowerCase();
                })
                .collect(Collectors.joining());
    }
}
