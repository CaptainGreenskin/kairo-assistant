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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;

@Tool(
        name = "git",
        description =
                "Git operations in a specified directory. Actions: status, log, diff, branch, "
                        + "show (show a commit). Read-only operations only.",
        category = ToolCategory.FILE_AND_CODE,
        sideEffect = ToolSideEffect.READ_ONLY)
public class GitTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: status, log, diff, branch, show."));
        props.put("directory", new JsonSchema("string", null, null,
                "Git repo directory. Default: current directory."));
        props.put("args", new JsonSchema("string", null, null,
                "Additional git arguments (e.g. '-n 5' for log, commit hash for show)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) {
            return ToolResult.error("git", "'action' required");
        }

        String dir = (String) args.getOrDefault("directory", ".");
        String extraArgs = (String) args.getOrDefault("args", "");

        String gitCmd = switch (action.toLowerCase()) {
            case "status" -> "git status --short";
            case "log" -> "git log --oneline " + extraArgs;
            case "diff" -> "git diff " + extraArgs;
            case "branch" -> "git branch -a";
            case "show" -> "git show " + extraArgs;
            default -> null;
        };

        if (gitCmd == null) {
            return ToolResult.error("git", "Unknown action: " + action);
        }

        try {
            Process process = new ProcessBuilder("sh", "-c", gitCmd)
                    .directory(Path.of(dir).toFile())
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("git", "Command timed out");
            }

            if (output.length() > 50_000) {
                output = output.substring(0, 50_000) + "\n... (truncated)";
            }

            if (output.isBlank()) {
                output = "(no output)";
            }

            return ToolResult.success("git", output,
                    Map.of("exitCode", process.exitValue()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.error("git", "Failed: " + e.getMessage());
        }
    }
}
