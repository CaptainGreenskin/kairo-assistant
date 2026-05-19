package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.assistant.tool.sandbox.SandboxBackend;
import io.kairo.assistant.tool.sandbox.SandboxFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "shell",
        description =
                "Execute a shell command and return stdout/stderr. "
                        + "Use for system operations, file management, etc. "
                        + "Execution backend is configurable (local/docker/ssh) via KAIRO_SANDBOX_MODE.",
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

        SandboxBackend sandbox = SandboxFactory.instance();
        SandboxBackend.SandboxResult result = sandbox.execute(command, timeout, workingDir);

        if (result.timedOut()) {
            return ToolResult.error("shell",
                    "Command timed out after " + timeout + "s. Partial output:\n" + result.output());
        }

        if (result.exitCode() != 0) {
            return ToolResult.error("shell",
                    "Exit code " + result.exitCode() + "\n" + result.output(),
                    Map.of("exitCode", result.exitCode()));
        }

        String output = result.output();
        return ToolResult.success("shell",
                output.isEmpty() ? "(no output)" : output,
                Map.of("exitCode", 0, "sandbox", sandbox.type()));
    }
}
