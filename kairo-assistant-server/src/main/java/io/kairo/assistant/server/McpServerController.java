package io.kairo.assistant.server;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.assistant.agent.AssistantSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/mcp")
public class McpServerController {

    private final AssistantSession session;

    public McpServerController(AssistantSession session) {
        this.session = session;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> handle(@RequestBody Map<String, Object> request) {
        String jsonrpc = (String) request.get("jsonrpc");
        Object id = request.get("id");
        String method = (String) request.get("method");

        if (!"2.0".equals(jsonrpc)) {
            return Mono.just(errorResponse(id, -32600, "Invalid JSON-RPC version"));
        }
        if (method == null) {
            return Mono.just(errorResponse(id, -32600, "Missing method"));
        }

        return switch (method) {
            case "initialize" -> Mono.just(successResponse(id, Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of(
                            "name", "kairo-assistant",
                            "version", "0.1.0"))));
            case "tools/list" -> Mono.just(successResponse(id, toolsList()));
            case "tools/call" -> toolsCall(id, request);
            case "ping" -> Mono.just(successResponse(id, Map.of()));
            default -> Mono.just(errorResponse(id, -32601, "Method not found: " + method));
        };
    }

    private Map<String, Object> toolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition def : session.toolRegistry().getAll()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", def.name());
            tool.put("description", def.description());
            tool.put("inputSchema", schemaToMap(def.inputSchema()));
            tools.add(tool);
        }
        return Map.of("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> toolsCall(Object id, Map<String, Object> request) {
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        if (params == null) {
            return Mono.just(errorResponse(id, -32602, "Missing params"));
        }

        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        if (toolName == null) {
            return Mono.just(errorResponse(id, -32602, "Missing tool name"));
        }

        return session.toolExecutor().execute(toolName, arguments)
                .map(result -> {
                    List<Map<String, Object>> content = new ArrayList<>();
                    content.add(Map.of("type", "text", "text", result.content()));
                    return successResponse(id, Map.of(
                            "content", content,
                            "isError", result.isError()));
                })
                .onErrorResume(e -> Mono.just(errorResponse(id, -32603, e.getMessage())));
    }

    private Map<String, Object> schemaToMap(JsonSchema schema) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", schema.type());
        if (schema.properties() != null) {
            Map<String, Object> props = new LinkedHashMap<>();
            schema.properties().forEach((k, v) -> props.put(k, schemaToMap(v)));
            map.put("properties", props);
        }
        if (schema.required() != null && !schema.required().isEmpty()) {
            map.put("required", schema.required());
        }
        if (schema.description() != null) {
            map.put("description", schema.description());
        }
        return map;
    }

    private Map<String, Object> successResponse(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        return resp;
    }

    private Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", Map.of("code", code, "message", message));
        return resp;
    }
}
