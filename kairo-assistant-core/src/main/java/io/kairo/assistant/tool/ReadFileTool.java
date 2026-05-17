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
        name = "read_file",
        description =
                "Read the contents of a file. Supports offset/limit for large files. "
                        + "Returns line-numbered content.",
        category = ToolCategory.FILE_AND_CODE,
        sideEffect = ToolSideEffect.READ_ONLY)
public class ReadFileTool implements SyncTool {

    private static final int MAX_LINES = 2000;

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("path", new JsonSchema("string", null, null, "Absolute file path to read."));
        props.put("offset", new JsonSchema("integer", null, null, "Line number to start from (0-based). Default 0."));
        props.put("limit", new JsonSchema("integer", null, null, "Max lines to read. Default 2000."));
        return new JsonSchema("object", props, List.of("path"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String pathStr = (String) args.get("path");
        if (pathStr == null || pathStr.isBlank()) {
            return ToolResult.error("read_file", "Parameter 'path' is required");
        }

        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            return ToolResult.error("read_file", "File not found: " + pathStr);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("read_file", "Path is a directory, not a file: " + pathStr);
        }

        int offset = 0;
        int limit = MAX_LINES;
        if (args.get("offset") instanceof Number n) offset = Math.max(0, n.intValue());
        if (args.get("limit") instanceof Number n) limit = Math.max(1, Math.min(MAX_LINES, n.intValue()));

        try {
            List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int end = Math.min(offset + limit, allLines.size());
            if (offset >= allLines.size()) {
                return ToolResult.success("read_file",
                        "File has " + allLines.size() + " lines, offset " + offset + " is past end.");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = offset; i < end; i++) {
                sb.append(i + 1).append('\t').append(allLines.get(i)).append('\n');
            }
            if (end < allLines.size()) {
                sb.append("... (").append(allLines.size() - end).append(" more lines)");
            }
            return ToolResult.success("read_file", sb.toString(),
                    Map.of("totalLines", allLines.size(), "readFrom", offset, "readTo", end));
        } catch (IOException e) {
            return ToolResult.error("read_file", "Failed to read: " + e.getMessage());
        }
    }
}
