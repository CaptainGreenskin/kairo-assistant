package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "list_directory",
        description = "List files and directories in a given path. Shows size and type.",
        category = ToolCategory.FILE_AND_CODE,
        sideEffect = ToolSideEffect.READ_ONLY)
public class ListDirectoryTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("path", new JsonSchema("string", null, null, "Directory path. Default: current directory."));
        props.put("recursive", new JsonSchema("boolean", null, null, "List recursively. Default false."));
        props.put("maxDepth", new JsonSchema("integer", null, null, "Max depth for recursive listing. Default 3."));
        return new JsonSchema("object", props, List.of(), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String pathStr = (String) args.getOrDefault("path", ".");
        boolean recursive = Boolean.TRUE.equals(args.get("recursive"));
        int maxDepth = 3;
        if (args.get("maxDepth") instanceof Number n) {
            maxDepth = Math.max(1, Math.min(10, n.intValue()));
        }

        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            return ToolResult.error("list_directory", "Path not found: " + pathStr);
        }
        if (!Files.isDirectory(path)) {
            return ToolResult.error("list_directory", "Not a directory: " + pathStr);
        }

        try {
            StringBuilder sb = new StringBuilder();
            listDir(path, sb, 0, maxDepth, recursive);
            return ToolResult.success("list_directory", sb.toString().trim());
        } catch (IOException e) {
            return ToolResult.error("list_directory", "Failed: " + e.getMessage());
        }
    }

    private void listDir(Path dir, StringBuilder sb, int depth, int maxDepth, boolean recursive)
            throws IOException {
        if (depth > maxDepth) return;
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        }
        entries.sort(Comparator.comparing(p -> p.getFileName().toString()));

        String indent = "  ".repeat(depth);
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                sb.append(indent).append(name).append("/\n");
                if (recursive && depth < maxDepth) {
                    listDir(entry, sb, depth + 1, maxDepth, true);
                }
            } else {
                long size = Files.size(entry);
                sb.append(indent).append(name).append("  (").append(humanSize(size)).append(")\n");
            }
        }
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
