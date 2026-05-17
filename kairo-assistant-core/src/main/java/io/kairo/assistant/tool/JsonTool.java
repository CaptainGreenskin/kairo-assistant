package io.kairo.assistant.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import reactor.core.publisher.Mono;

@Tool(
        name = "json",
        description =
                "JSON utilities. Actions: format (pretty-print), validate (check JSON syntax), "
                        + "query (extract value by JSON Pointer, e.g. /data/0/name), "
                        + "minify (compact form).",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.READ_ONLY)
public class JsonTool implements SyncTool {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ObjectMapper compact = new ObjectMapper();

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: format, validate, query, minify."));
        props.put("json", new JsonSchema("string", null, null,
                "JSON string to process."));
        props.put("pointer", new JsonSchema("string", null, null,
                "JSON Pointer path for query (e.g. /data/0/name)."));
        return new JsonSchema("object", props, List.of("action", "json"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        String json = (String) args.get("json");
        if (json == null || json.isBlank()) {
            return ToolResult.error("json", "'json' required");
        }

        return switch (action != null ? action.toLowerCase() : "") {
            case "format" -> doFormat(json);
            case "validate" -> doValidate(json);
            case "query" -> doQuery(json, (String) args.get("pointer"));
            case "minify" -> doMinify(json);
            default -> ToolResult.error("json",
                    "Unknown action: " + action + ". Use format/validate/query/minify.");
        };
    }

    private ToolResult doFormat(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return ToolResult.success("json", mapper.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            return ToolResult.error("json", "Invalid JSON: " + e.getOriginalMessage());
        }
    }

    private ToolResult doValidate(String json) {
        try {
            mapper.readTree(json);
            return ToolResult.success("json", "Valid JSON.");
        } catch (JsonProcessingException e) {
            return ToolResult.error("json",
                    "Invalid JSON at line " + e.getLocation().getLineNr()
                            + ": " + e.getOriginalMessage());
        }
    }

    private ToolResult doQuery(String json, String pointer) {
        if (pointer == null || pointer.isBlank()) {
            return ToolResult.error("json", "'pointer' required for query");
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode result = root.at(pointer);
            if (result.isMissingNode()) {
                return ToolResult.success("json",
                        "No value at pointer: " + pointer);
            }
            return ToolResult.success("json", mapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            return ToolResult.error("json", "Invalid JSON: " + e.getOriginalMessage());
        }
    }

    private ToolResult doMinify(String json) {
        try {
            JsonNode node = compact.readTree(json);
            return ToolResult.success("json", compact.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            return ToolResult.error("json", "Invalid JSON: " + e.getOriginalMessage());
        }
    }
}
