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

        // --no-pager: large outputs (diff/show/log without --oneline) otherwise
        // wedge for 30s+ when git decides to invoke `less` even on a non-tty
        // subprocess. Manifests as the tool timing out rather than returning
        // results.
        String gitCmd = switch (action.toLowerCase()) {
            case "status" -> "git --no-pager status --short";
            case "log" -> "git --no-pager log --oneline " + extraArgs;
            case "diff" -> "git --no-pager diff " + extraArgs;
            case "branch" -> "git --no-pager branch -a";
            case "show" -> "git --no-pager show " + extraArgs;
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
            try {
                // Drain stdout concurrently. Otherwise large outputs (e.g.
                // `git diff` on a dirty repo) fill the 64KB pipe buffer, git
                // blocks writing, and process.waitFor() deadlocks for the
                // full 30s window.
                final java.util.concurrent.CompletableFuture<String> stdout =
                        new java.util.concurrent.CompletableFuture<>();
                Thread drain = new Thread(() -> {
                    try (var is = process.getInputStream()) {
                        stdout.complete(new String(
                                is.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException ioe) {
                        stdout.completeExceptionally(ioe);
                    }
                }, "git-stdout-drain");
                drain.setDaemon(true);
                drain.start();

                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    drain.interrupt();
                    return ToolResult.error("git", "Command timed out");
                }

                String output;
                try {
                    output = stdout.get(2, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException
                         | java.util.concurrent.TimeoutException ex) {
                    return ToolResult.error("git",
                            "Failed to read git output: " + ex.getMessage());
                }

                output = ToolLimits.truncate(output);

                if (output.isBlank()) {
                    output = "(no output)";
                }

                return ToolResult.success("git", output,
                        Map.of("exitCode", process.exitValue()));
            } finally {
                process.destroyForcibly();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.error("git", "Failed: " + e.getMessage());
        }
    }
}
