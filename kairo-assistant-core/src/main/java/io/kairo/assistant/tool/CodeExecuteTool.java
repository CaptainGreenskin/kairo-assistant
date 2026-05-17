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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;

@Tool(
        name = "execute_code",
        description =
                "Execute a code snippet in Python, Node.js, or shell. "
                        + "Writes code to a temp file and runs it. Returns stdout/stderr.",
        category = ToolCategory.EXECUTION,
        timeoutSeconds = 60,
        sideEffect = ToolSideEffect.WRITE)
public class CodeExecuteTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("code", new JsonSchema("string", null, null, "Code to execute."));
        props.put("language", new JsonSchema("string", null, null,
                "Language: 'python', 'node', 'bash'. Default 'python'."));
        props.put("timeout", new JsonSchema("integer", null, null, "Timeout in seconds. Default 30."));
        return new JsonSchema("object", props, List.of("code"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String code = (String) args.get("code");
        if (code == null || code.isBlank()) {
            return ToolResult.error("execute_code", "Parameter 'code' is required");
        }

        String language = (String) args.getOrDefault("language", "python");
        int timeout = 30;
        if (args.get("timeout") instanceof Number n) {
            timeout = Math.max(1, Math.min(120, n.intValue()));
        }

        String suffix = switch (language.toLowerCase()) {
            case "node", "javascript", "js" -> ".js";
            case "bash", "sh", "shell" -> ".sh";
            default -> ".py";
        };
        String interpreter = switch (language.toLowerCase()) {
            case "node", "javascript", "js" -> "node";
            case "bash", "sh", "shell" -> "bash";
            default -> "python3";
        };

        try {
            Path tmpFile = Files.createTempFile("kairo-exec-", suffix);
            Files.writeString(tmpFile, code, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(interpreter, tmpFile.toString())
                    .redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int chars = 0;
                while ((line = reader.readLine()) != null && chars < 50_000) {
                    output.append(line).append('\n');
                    chars += line.length();
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            Files.deleteIfExists(tmpFile);

            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("execute_code",
                        "Timed out after " + timeout + "s. Partial:\n" + output);
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (exitCode != 0) {
                return ToolResult.error("execute_code",
                        "Exit code " + exitCode + "\n" + result,
                        Map.of("exitCode", exitCode, "language", language));
            }
            return ToolResult.success("execute_code",
                    result.isEmpty() ? "(no output)" : result,
                    Map.of("exitCode", 0, "language", language));
        } catch (Exception e) {
            return ToolResult.error("execute_code", "Execution failed: " + e.getMessage());
        }
    }
}
