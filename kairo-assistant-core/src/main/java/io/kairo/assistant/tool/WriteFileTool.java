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
        name = "write_file",
        description = "Write content to a file. Creates parent directories if needed. Overwrites existing content.",
        category = ToolCategory.FILE_AND_CODE,
        sideEffect = ToolSideEffect.WRITE)
public class WriteFileTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("path", new JsonSchema("string", null, null, "Absolute file path to write."));
        props.put("content", new JsonSchema("string", null, null, "Content to write."));
        return new JsonSchema("object", props, List.of("path", "content"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String pathStr = (String) args.get("path");
        String content = (String) args.get("content");
        if (pathStr == null || pathStr.isBlank()) {
            return ToolResult.error("write_file", "Parameter 'path' is required");
        }
        if (content == null) {
            content = "";
        }

        Path path = Path.of(pathStr);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
            long bytes = content.getBytes(StandardCharsets.UTF_8).length;
            return ToolResult.success("write_file",
                    "Written " + bytes + " bytes to " + pathStr,
                    Map.of("bytes", bytes));
        } catch (IOException e) {
            return ToolResult.error("write_file", "Failed to write: " + e.getMessage());
        }
    }
}
