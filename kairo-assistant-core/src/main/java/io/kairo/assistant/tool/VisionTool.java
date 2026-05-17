package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

@Tool(
        name = "vision",
        description =
                "Analyze an image file using the model's vision capabilities. "
                        + "Provide a file path and an optional prompt describing what to analyze. "
                        + "Supports PNG, JPEG, GIF, and WebP formats.",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class VisionTool implements SyncTool {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");

    private static final Map<String, String> EXT_TO_MIME = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024;

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("file_path", new JsonSchema("string", null, null,
                "Path to the image file to analyze."));
        props.put("prompt", new JsonSchema("string", null, null,
                "What to analyze in the image. Default: 'Describe this image in detail.'"));
        return new JsonSchema("object", props, List.of("file_path"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.defer(() -> {
            String filePath = (String) args.get("file_path");
            if (filePath == null || filePath.isBlank()) {
                return Mono.just(ToolResult.error("vision", "Parameter 'file_path' is required"));
            }

            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return Mono.just(ToolResult.error("vision", "File not found: " + filePath));
            }
            if (!Files.isRegularFile(path)) {
                return Mono.just(ToolResult.error("vision", "Not a regular file: " + filePath));
            }

            try {
                long size = Files.size(path);
                if (size > MAX_FILE_SIZE) {
                    return Mono.just(ToolResult.error("vision",
                            "File too large: " + (size / 1024 / 1024) + "MB (max 20MB)"));
                }
            } catch (IOException e) {
                return Mono.just(ToolResult.error("vision", "Cannot read file size: " + e.getMessage()));
            }

            String ext = getExtension(path.getFileName().toString()).toLowerCase();
            String mimeType = EXT_TO_MIME.get(ext);
            if (mimeType == null) {
                return Mono.just(ToolResult.error("vision",
                        "Unsupported format: " + ext + ". Supported: " + EXT_TO_MIME.keySet()));
            }

            String prompt = (String) args.getOrDefault("prompt", "Describe this image in detail.");

            try {
                byte[] bytes = Files.readAllBytes(path);
                String base64 = Base64.getEncoder().encodeToString(bytes);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("imageBase64", base64);
                metadata.put("mimeType", mimeType);
                metadata.put("prompt", prompt);
                metadata.put("fileName", path.getFileName().toString());

                return Mono.just(ToolResult.success("vision",
                        "[Image loaded: " + path.getFileName()
                                + " (" + mimeType + ", " + bytes.length + " bytes)]\n"
                                + "Analysis prompt: " + prompt,
                        metadata));
            } catch (IOException e) {
                return Mono.just(ToolResult.error("vision", "Failed to read image: " + e.getMessage()));
            }
        });
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }
}
