package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.io.BufferedReader;
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
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

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
            long fileSize = Files.size(path);
            if (fileSize > MAX_FILE_SIZE) {
                return ToolResult.error("read_file",
                        "File too large (" + fileSize / (1024 * 1024) + "MB, max 10MB). Use offset/limit for partial reads.");
            }

            StringBuilder sb = new StringBuilder();
            int lineNum = 0;
            int linesRead = 0;
            boolean hasMore = false;

            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lineNum >= offset && linesRead < limit) {
                        sb.append(lineNum + 1).append('\t').append(line).append('\n');
                        linesRead++;
                    } else if (linesRead >= limit) {
                        hasMore = true;
                        break;
                    }
                    lineNum++;
                }
                if (!hasMore) {
                    while (reader.readLine() != null) {
                        lineNum++;
                        hasMore = true;
                    }
                }
            }

            int totalLines = lineNum + (hasMore ? 1 : 0);
            if (linesRead == 0 && offset > 0) {
                return ToolResult.success("read_file",
                        "File has " + totalLines + " lines, offset " + offset + " is past end.");
            }
            if (hasMore) {
                sb.append("... (more lines available, total ~").append(totalLines).append(")");
            }
            return ToolResult.success("read_file", sb.toString(),
                    Map.of("linesRead", linesRead, "readFrom", offset, "readTo", offset + linesRead));
        } catch (IOException e) {
            return ToolResult.error("read_file", "Failed to read: " + e.getMessage());
        }
    }
}
