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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;

@Tool(
        name = "shell",
        description =
                "Execute a shell command and return stdout/stderr. "
                        + "Use for system operations, file management, etc.",
        category = ToolCategory.EXECUTION,
        timeoutSeconds = 120,
        sideEffect = ToolSideEffect.WRITE)
public class ShellTool implements SyncTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put(
                "command",
                new JsonSchema("string", null, null, "Shell command to execute."));
        props.put(
                "timeout",
                new JsonSchema(
                        "integer",
                        null,
                        null,
                        "Timeout in seconds (default 60, max 300)."));
        props.put(
                "workingDir",
                new JsonSchema(
                        "string",
                        null,
                        null,
                        "Working directory for the command."));
        return new JsonSchema("object", props, List.of("command"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String command = (String) args.get("command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("shell", "Parameter 'command' is required");
        }

        int timeout = DEFAULT_TIMEOUT_SECONDS;
        Object timeoutRaw = args.get("timeout");
        if (timeoutRaw instanceof Number n) {
            timeout = Math.max(1, Math.min(300, n.intValue()));
        }

        String workingDir = (String) args.get("workingDir");

        try {
            ProcessBuilder pb =
                    new ProcessBuilder("sh", "-c", command).redirectErrorStream(true);
            if (workingDir != null && !workingDir.isBlank()) {
                pb.directory(new java.io.File(workingDir));
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int charCount = 0;
                while ((line = reader.readLine()) != null && charCount < ToolLimits.MAX_OUTPUT_CHARS) {
                    output.append(line).append("\n");
                    charCount += line.length();
                }
                if (charCount >= ToolLimits.MAX_OUTPUT_CHARS) {
                    output.append("\n... (output truncated at ").append(ToolLimits.MAX_OUTPUT_CHARS / 1000).append("K chars)");
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error(
                        "shell",
                        "Command timed out after " + timeout + "s. Partial output:\n" + output);
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (exitCode != 0) {
                return ToolResult.error(
                        "shell",
                        "Exit code " + exitCode + "\n" + result,
                        Map.of("exitCode", exitCode));
            }
            return ToolResult.success(
                    "shell",
                    result.isEmpty() ? "(no output)" : result,
                    Map.of("exitCode", 0));
        } catch (Exception e) {
            return ToolResult.error("shell", "Failed to execute: " + e.getMessage());
        }
    }
}
