package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "patch",
        description =
                "Apply a search-and-replace edit to a file. Finds old_string and replaces with new_string. "
                        + "Fails if old_string is not found or is ambiguous.",
        category = ToolCategory.FILE_AND_CODE,
        sideEffect = ToolSideEffect.WRITE)
public class PatchTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("path", new JsonSchema("string", null, null, "Absolute file path to edit."));
        props.put("old_string", new JsonSchema("string", null, null, "Exact text to find and replace."));
        props.put("new_string", new JsonSchema("string", null, null, "Replacement text."));
        props.put("replace_all", new JsonSchema("boolean", null, null, "Replace all occurrences (default false)."));
        return new JsonSchema("object", props, List.of("path", "old_string", "new_string"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String pathStr = (String) args.get("path");
        String oldString = (String) args.get("old_string");
        String newString = (String) args.get("new_string");
        boolean replaceAll = Boolean.TRUE.equals(args.get("replace_all"));

        if (pathStr == null) return ToolResult.error("patch", "'path' is required");
        if (oldString == null) return ToolResult.error("patch", "'old_string' is required");
        if (newString == null) return ToolResult.error("patch", "'new_string' is required");

        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            return ToolResult.error("patch", "File not found: " + pathStr);
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return ToolResult.error("patch", "old_string not found in file");
            }
            if (!replaceAll && count > 1) {
                return ToolResult.error("patch",
                        "old_string found " + count + " times. Use replace_all=true or provide more context.");
            }

            String updated = replaceAll
                    ? content.replace(oldString, newString)
                    : content.replaceFirst(java.util.regex.Pattern.quote(oldString),
                            java.util.regex.Matcher.quoteReplacement(newString));
            Files.writeString(path, updated, StandardCharsets.UTF_8);

            int replaced = replaceAll ? count : 1;
            return ToolResult.success("patch",
                    "Replaced " + replaced + " occurrence(s) in " + pathStr,
                    Map.of("replacements", replaced));
        } catch (IOException e) {
            return ToolResult.error("patch", "Failed: " + e.getMessage());
        }
    }

    private int countOccurrences(String content, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }
}
