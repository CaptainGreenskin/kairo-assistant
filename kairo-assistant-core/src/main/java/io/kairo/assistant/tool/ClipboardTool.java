package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "clipboard",
        description =
                "Read from or write to the system clipboard. "
                        + "Actions: read (get clipboard content), write (set clipboard content).",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.WRITE)
public class ClipboardTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: read or write."));
        props.put("content", new JsonSchema("string", null, null,
                "Content to copy to clipboard (for write)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) {
            return ToolResult.error("clipboard", "'action' required");
        }

        String os = System.getProperty("os.name", "").toLowerCase();

        return switch (action.toLowerCase()) {
            case "read" -> readClipboard(os);
            case "write" -> {
                String content = (String) args.get("content");
                if (content == null) {
                    yield ToolResult.error("clipboard", "'content' required for write");
                }
                yield writeClipboard(os, content);
            }
            default -> ToolResult.error("clipboard",
                    "Unknown action: " + action + ". Use read/write.");
        };
    }

    private ToolResult readClipboard(String os) {
        try {
            String[] cmd = clipboardReadCmd(os);
            if (cmd == null) {
                return ToolResult.error("clipboard",
                        "Clipboard not supported on this OS: " + os);
            }
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String content;
            try (var is = p.getInputStream()) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exit = p.waitFor();
            p.destroyForcibly();
            if (exit != 0) {
                return ToolResult.error("clipboard", "Clipboard read failed (exit=" + exit + ")");
            }
            if (content.isEmpty()) {
                return ToolResult.success("clipboard", "(clipboard is empty)");
            }
            return ToolResult.success("clipboard", content);
        } catch (Exception e) {
            return ToolResult.error("clipboard", "Read failed: " + e.getMessage());
        }
    }

    private ToolResult writeClipboard(String os, String content) {
        try {
            String[] cmd = clipboardWriteCmd(os);
            if (cmd == null) {
                return ToolResult.error("clipboard",
                        "Clipboard not supported on this OS: " + os);
            }
            Process p = new ProcessBuilder(cmd).start();
            try (OutputStream out = p.getOutputStream()) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            int exit = p.waitFor();
            if (exit != 0) {
                return ToolResult.error("clipboard", "Clipboard write failed (exit=" + exit + ")");
            }
            return ToolResult.success("clipboard",
                    "Copied " + content.length() + " chars to clipboard.");
        } catch (Exception e) {
            return ToolResult.error("clipboard", "Write failed: " + e.getMessage());
        }
    }

    private String[] clipboardReadCmd(String os) {
        if (os.contains("mac")) return new String[]{"pbpaste"};
        if (os.contains("linux")) return new String[]{"xclip", "-selection", "clipboard", "-o"};
        if (os.contains("win")) return new String[]{"powershell", "-command", "Get-Clipboard"};
        return null;
    }

    private String[] clipboardWriteCmd(String os) {
        if (os.contains("mac")) return new String[]{"pbcopy"};
        if (os.contains("linux")) return new String[]{"xclip", "-selection", "clipboard"};
        if (os.contains("win")) return new String[]{"powershell", "-command", "Set-Clipboard", "-Value", "-"};
        return null;
    }
}
