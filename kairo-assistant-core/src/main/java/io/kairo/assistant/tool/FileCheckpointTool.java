package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.api.workspace.Workspace;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

@Tool(
        name = "file_checkpoint",
        description =
                "Create and restore filesystem checkpoints using git. "
                        + "Automatically snapshots working directory state before destructive operations. "
                        + "Actions: create (save current state), restore (revert to checkpoint), "
                        + "list (show all checkpoints), delete (remove a checkpoint).",
        category = ToolCategory.FILE_AND_CODE,
        sideEffect = ToolSideEffect.WRITE)
public class FileCheckpointTool implements SyncTool {

    private static final String TAG_PREFIX = "kairo-checkpoint/";
    private static final int MAX_CHECKPOINTS = 10;

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "'create' to snapshot, 'restore' to revert, 'list' to show all, 'delete' to remove."));
        props.put("label", new JsonSchema("string", null, null,
                "Checkpoint label (for create/restore/delete). Auto-generated if omitted for create."));
        props.put("message", new JsonSchema("string", null, null,
                "Description of what's being checkpointed (for create)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args, ctx));
    }

    private ToolResult doExecute(Map<String, Object> args, ToolContext ctx) {
        String action = (String) args.get("action");
        if (action == null || action.isBlank()) {
            return ToolResult.error("file_checkpoint", "'action' required");
        }

        Path workDir = ctx.workspace() != null ? ctx.workspace().root() : Path.of(".");

        if (!isGitRepo(workDir)) {
            return ToolResult.error("file_checkpoint",
                    "Not a git repository. File checkpoints require git.");
        }

        return switch (action) {
            case "create" -> createCheckpoint(args, workDir);
            case "restore" -> restoreCheckpoint(args, workDir);
            case "list" -> listCheckpoints(workDir);
            case "delete" -> deleteCheckpoint(args, workDir);
            default -> ToolResult.error("file_checkpoint", "Unknown action: " + action);
        };
    }

    private ToolResult createCheckpoint(Map<String, Object> args, Path workDir) {
        String label = args.get("label") instanceof String l && !l.isBlank()
                ? l : "cp-" + Instant.now().getEpochSecond();
        String message = args.get("message") instanceof String m ? m : "checkpoint";

        String tagName = TAG_PREFIX + label;

        String stageResult = git(workDir, "add", "-A");
        if (stageResult == null) {
            return ToolResult.error("file_checkpoint", "Failed to stage files");
        }

        String status = git(workDir, "status", "--porcelain");
        if (status != null && status.isBlank()) {
            String headRef = git(workDir, "rev-parse", "HEAD");
            if (headRef == null || headRef.isBlank()) {
                return ToolResult.error("file_checkpoint", "No commits in repository");
            }
            String tagResult = git(workDir, "tag", "-f", tagName, headRef.trim());
            if (tagResult == null) {
                return ToolResult.error("file_checkpoint", "Failed to create tag");
            }
        } else {
            String commitResult = git(workDir, "commit", "--allow-empty", "-m",
                    "checkpoint: " + message);
            if (commitResult == null) {
                return ToolResult.error("file_checkpoint", "Failed to create checkpoint commit");
            }
            String tagResult = git(workDir, "tag", "-f", tagName);
            if (tagResult == null) {
                return ToolResult.error("file_checkpoint", "Failed to tag checkpoint");
            }
        }

        pruneOldCheckpoints(workDir);

        return ToolResult.success("file_checkpoint",
                "Checkpoint created: " + label + "\nMessage: " + message);
    }

    private ToolResult restoreCheckpoint(Map<String, Object> args, Path workDir) {
        String label = (String) args.get("label");
        if (label == null || label.isBlank()) {
            return ToolResult.error("file_checkpoint", "'label' required for restore");
        }

        String tagName = TAG_PREFIX + label;
        String tagExists = git(workDir, "tag", "-l", tagName);
        if (tagExists == null || tagExists.isBlank()) {
            return ToolResult.error("file_checkpoint", "Checkpoint not found: " + label);
        }

        String resetResult = git(workDir, "checkout", tagName, "--", ".");
        if (resetResult == null) {
            String altResult = git(workDir, "restore", "--source=" + tagName, ".");
            if (altResult == null) {
                return ToolResult.error("file_checkpoint",
                        "Failed to restore checkpoint: " + label);
            }
        }

        return ToolResult.success("file_checkpoint",
                "Restored workspace to checkpoint: " + label);
    }

    private ToolResult listCheckpoints(Path workDir) {
        String tags = git(workDir, "tag", "-l", TAG_PREFIX + "*");
        if (tags == null || tags.isBlank()) {
            return ToolResult.success("file_checkpoint", "No file checkpoints found.");
        }

        String[] tagList = tags.split("\n");
        StringBuilder sb = new StringBuilder("File checkpoints:\n");
        for (String tag : tagList) {
            String label = tag.replace(TAG_PREFIX, "");
            String commitInfo = git(workDir, "log", "-1", "--format=%s (%cr)", tag.trim());
            sb.append("- ").append(label);
            if (commitInfo != null && !commitInfo.isBlank()) {
                sb.append(" — ").append(commitInfo.trim());
            }
            sb.append("\n");
        }
        return ToolResult.success("file_checkpoint", sb.toString());
    }

    private ToolResult deleteCheckpoint(Map<String, Object> args, Path workDir) {
        String label = (String) args.get("label");
        if (label == null || label.isBlank()) {
            return ToolResult.error("file_checkpoint", "'label' required for delete");
        }
        String tagName = TAG_PREFIX + label;
        String result = git(workDir, "tag", "-d", tagName);
        if (result == null) {
            return ToolResult.error("file_checkpoint", "Checkpoint not found: " + label);
        }
        return ToolResult.success("file_checkpoint", "Deleted checkpoint: " + label);
    }

    private void pruneOldCheckpoints(Path workDir) {
        String tags = git(workDir, "tag", "-l", TAG_PREFIX + "*", "--sort=creatordate");
        if (tags == null || tags.isBlank()) return;

        String[] tagList = tags.split("\n");
        if (tagList.length > MAX_CHECKPOINTS) {
            int toRemove = tagList.length - MAX_CHECKPOINTS;
            for (int i = 0; i < toRemove; i++) {
                git(workDir, "tag", "-d", tagList[i].trim());
            }
        }
    }

    private boolean isGitRepo(Path workDir) {
        String result = git(workDir, "rev-parse", "--is-inside-work-tree");
        return "true".equals(result != null ? result.trim() : "");
    }

    private String git(Path workDir, String... gitArgs) {
        Process process = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (String arg : gitArgs) {
                cmd.add(arg);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();

            // Drain stdout on a daemon thread so a chatty git can't deadlock on a full pipe.
            final Process p = process;
            CompletableFuture<String> stdout = new CompletableFuture<>();
            Thread drain = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    stdout.complete(reader.lines().collect(Collectors.joining("\n")));
                } catch (IOException ioe) {
                    stdout.completeExceptionally(ioe);
                }
            }, "checkpoint-git-drain");
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                drain.interrupt();
                return null;
            }
            String output = stdout.get(2, TimeUnit.SECONDS);
            return process.exitValue() == 0 ? output : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }
}
