package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

@Tool(
        name = "mcp_client",
        description =
                "Connect to MCP (Model Context Protocol) servers and invoke their tools. "
                        + "Supports listing tools and calling specific MCP server tools.",
        category = ToolCategory.EXTERNAL,
        sideEffect = ToolSideEffect.WRITE)
public class McpClientTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "'list_tools' to list available tools on MCP server, 'call' to invoke a tool."));
        props.put("server_url", new JsonSchema("string", null, null,
                "MCP server URL (e.g., 'http://localhost:3000')."));
        props.put("tool_name", new JsonSchema("string", null, null,
                "Name of the MCP tool to invoke (for 'call' action)."));
        props.put("arguments", new JsonSchema("string", null, null,
                "JSON string of arguments to pass to the MCP tool."));
        return new JsonSchema("object", props, List.of("action", "server_url"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        String serverUrl = (String) args.get("server_url");

        if (action == null || serverUrl == null) {
            return ToolResult.error("mcp_client", "'action' and 'server_url' required");
        }

        return switch (action) {
            case "list_tools" -> listTools(serverUrl);
            case "call" -> callTool(args, serverUrl);
            default -> ToolResult.error("mcp_client", "Unknown action: " + action);
        };
    }

    private ToolResult listTools(String serverUrl) {
        try {
            String jsonRpc = String.format(
                    """
                    {"jsonrpc":"2.0","id":"%s","method":"tools/list","params":{}}
                    """,
                    UUID.randomUUID());

            HttpResponse<String> resp = sendJsonRpc(serverUrl, jsonRpc);
            if (resp.statusCode() == 200) {
                return ToolResult.success("mcp_client",
                        "MCP tools:\n" + resp.body());
            }
            return ToolResult.error("mcp_client",
                    "HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            return ToolResult.error("mcp_client", "Failed to list tools: " + e.getMessage());
        }
    }

    private ToolResult callTool(Map<String, Object> args, String serverUrl) {
        String toolName = (String) args.get("tool_name");
        String arguments = args.get("arguments") instanceof String a ? a : "{}";

        if (toolName == null || toolName.isBlank()) {
            return ToolResult.error("mcp_client", "'tool_name' required for call action");
        }

        try {
            String jsonRpc = String.format(
                    """
                    {"jsonrpc":"2.0","id":"%s","method":"tools/call","params":{"name":"%s","arguments":%s}}
                    """,
                    UUID.randomUUID(),
                    toolName.replace("\"", "\\\""),
                    arguments);

            HttpResponse<String> resp = sendJsonRpc(serverUrl, jsonRpc);
            if (resp.statusCode() == 200) {
                return ToolResult.success("mcp_client",
                        "MCP tool result:\n" + resp.body());
            }
            return ToolResult.error("mcp_client",
                    "HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            return ToolResult.error("mcp_client", "Failed to call tool: " + e.getMessage());
        }
    }

    private HttpResponse<String> sendJsonRpc(String serverUrl, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
