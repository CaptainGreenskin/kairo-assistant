package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;

@Tool(
        name = "search_files",
        description =
                "Search for text patterns in files using grep. Supports regex. "
                        + "Returns matching lines with file path and line number.",
        category = ToolCategory.FILE_AND_CODE,
        sideEffect = ToolSideEffect.READ_ONLY)
public class SearchFilesTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("pattern", new JsonSchema("string", null, null, "Search pattern (regex supported)."));
        props.put("path", new JsonSchema("string", null, null, "Directory or file to search in. Default: current dir."));
        props.put("include", new JsonSchema("string", null, null, "File glob pattern to include (e.g., '*.java')."));
        props.put("maxResults", new JsonSchema("integer", null, null, "Max results to return. Default 50."));
        return new JsonSchema("object", props, List.of("pattern"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("search_files", "Parameter 'pattern' is required");
        }

        String path = (String) args.getOrDefault("path", ".");
        String include = (String) args.get("include");
        int maxResults = 50;
        if (args.get("maxResults") instanceof Number n) {
            maxResults = Math.max(1, Math.min(200, n.intValue()));
        }

        try {
            StringBuilder cmd = new StringBuilder("grep -rn --color=never");
            if (include != null && !include.isBlank()) {
                cmd.append(" --include='").append(include).append("'");
            }
            cmd.append(" -m ").append(maxResults);
            cmd.append(" -- '").append(pattern.replace("'", "'\\''")).append("'");
            cmd.append(" '").append(path.replace("'", "'\\''")).append("'");

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd.toString()).redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count < maxResults) {
                    output.append(line).append('\n');
                    count++;
                }
            }

            process.waitFor(30, TimeUnit.SECONDS);
            String result = output.toString().trim();
            if (result.isEmpty()) {
                return ToolResult.success("search_files", "No matches found for: " + pattern);
            }
            return ToolResult.success("search_files", result);
        } catch (Exception e) {
            return ToolResult.error("search_files", "Search failed: " + e.getMessage());
        }
    }
}
