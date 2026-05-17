package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

@Tool(
        name = "process",
        description =
                "List or search running processes. Actions: list (top processes by CPU), "
                        + "search (find by name), info (process details by PID).",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class ProcessTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: list, search, info. Default: list."));
        props.put("query", new JsonSchema("string", null, null,
                "Process name to search (for search action)."));
        props.put("pid", new JsonSchema("integer", null, null,
                "Process ID (for info action)."));
        props.put("limit", new JsonSchema("integer", null, null,
                "Max results for list. Default 20."));
        return new JsonSchema("object", props, List.of(), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.getOrDefault("action", "list");
        int limit = 20;
        if (args.get("limit") instanceof Number n) limit = n.intValue();

        return switch (action.toLowerCase()) {
            case "list" -> {
                List<String> processes = ProcessHandle.allProcesses()
                        .limit(limit)
                        .map(this::formatProcess)
                        .collect(Collectors.toList());
                yield ToolResult.success("process",
                        processes.size() + " processes:\n" + String.join("\n", processes));
            }
            case "search" -> {
                String query = (String) args.get("query");
                if (query == null) yield ToolResult.error("process", "'query' required");
                else {
                    String qLower = query.toLowerCase();
                    List<String> matches = ProcessHandle.allProcesses()
                            .filter(p -> p.info().command().orElse("").toLowerCase().contains(qLower)
                                    || p.info().commandLine().orElse("").toLowerCase().contains(qLower))
                            .limit(limit)
                            .map(this::formatProcess)
                            .collect(Collectors.toList());
                    if (matches.isEmpty()) {
                        yield ToolResult.success("process", "No processes match: " + query);
                    }
                    yield ToolResult.success("process",
                            matches.size() + " matches:\n" + String.join("\n", matches));
                }
            }
            case "info" -> {
                if (!(args.get("pid") instanceof Number n)) {
                    yield ToolResult.error("process", "'pid' required for info");
                }
                long pid = n.longValue();
                ProcessHandle.of(pid)
                        .map(this::formatProcessDetail)
                        .map(s -> ToolResult.success("process", s))
                        .orElse(ToolResult.error("process", "PID not found: " + pid));
                yield ProcessHandle.of(pid)
                        .map(this::formatProcessDetail)
                        .map(s -> ToolResult.success("process", s))
                        .orElse(ToolResult.error("process", "PID not found: " + pid));
            }
            default -> ToolResult.error("process", "Unknown action: " + action);
        };
    }

    private String formatProcess(ProcessHandle p) {
        return String.format("PID=%d  %s", p.pid(),
                p.info().command().orElse("(unknown)"));
    }

    private String formatProcessDetail(ProcessHandle p) {
        ProcessHandle.Info info = p.info();
        StringBuilder sb = new StringBuilder();
        sb.append("PID: ").append(p.pid()).append("\n");
        info.command().ifPresent(c -> sb.append("Command: ").append(c).append("\n"));
        info.commandLine().ifPresent(c -> sb.append("Command line: ").append(c).append("\n"));
        info.user().ifPresent(u -> sb.append("User: ").append(u).append("\n"));
        info.startInstant().ifPresent(s -> sb.append("Started: ").append(s).append("\n"));
        info.totalCpuDuration().ifPresent(d -> sb.append("CPU time: ").append(d).append("\n"));
        sb.append("Alive: ").append(p.isAlive());
        return sb.toString();
    }
}
