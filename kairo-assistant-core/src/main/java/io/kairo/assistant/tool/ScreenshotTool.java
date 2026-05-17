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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "screenshot",
        description = "Take a screenshot of the desktop or a specific window (macOS/Linux).",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.READ_ONLY)
public class ScreenshotTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("output", new JsonSchema("string", null, null,
                "Output file path. Default: /tmp/kairo-screenshot.png"));
        props.put("region", new JsonSchema("string", null, null,
                "Screen region: 'full' (default), 'selection' (interactive)."));
        return new JsonSchema("object", props, List.of(), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String output = args.get("output") instanceof String o ? o : "/tmp/kairo-screenshot.png";
        String os = System.getProperty("os.name", "").toLowerCase();

        try {
            ProcessBuilder pb;
            if (os.contains("mac")) {
                pb = new ProcessBuilder("screencapture", "-x", output);
            } else if (os.contains("linux")) {
                if (commandExists("gnome-screenshot")) {
                    pb = new ProcessBuilder("gnome-screenshot", "-f", output);
                } else if (commandExists("scrot")) {
                    pb = new ProcessBuilder("scrot", output);
                } else {
                    return ToolResult.error("screenshot", "No screenshot tool found (install scrot or gnome-screenshot)");
                }
            } else {
                return ToolResult.error("screenshot", "Screenshot not supported on " + os);
            }

            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0 && Files.exists(Path.of(output))) {
                long size = Files.size(Path.of(output));
                return ToolResult.success("screenshot",
                        "Screenshot saved: " + output + " (" + size + " bytes)");
            }
            return ToolResult.error("screenshot", "Screenshot command failed (exit " + exitCode + ")");
        } catch (Exception e) {
            return ToolResult.error("screenshot", "Failed: " + e.getMessage());
        }
    }

    private boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
