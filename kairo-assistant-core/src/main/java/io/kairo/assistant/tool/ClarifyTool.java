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
import reactor.core.publisher.Mono;

@Tool(
        name = "clarify",
        description =
                "Ask the user a clarifying question when the request is ambiguous or "
                        + "when you need more information to proceed. Present options if applicable.",
        category = ToolCategory.AGENT_AND_TASK,
        sideEffect = ToolSideEffect.READ_ONLY)
public class ClarifyTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("question", new JsonSchema("string", null, null,
                "The clarifying question to ask the user."));
        props.put("options", new JsonSchema("string", null, null,
                "Comma-separated list of options, if applicable (e.g. 'Option A, Option B, Option C')."));
        props.put("context", new JsonSchema("string", null, null,
                "Brief context about why this clarification is needed."));
        return new JsonSchema("object", props, List.of("question"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.defer(() -> {
            String question = (String) args.get("question");
            if (question == null || question.isBlank()) {
                return Mono.just(ToolResult.error("clarify", "Parameter 'question' is required"));
            }

            StringBuilder sb = new StringBuilder();

            String contextStr = (String) args.get("context");
            if (contextStr != null && !contextStr.isBlank()) {
                sb.append("Context: ").append(contextStr).append("\n\n");
            }

            sb.append("Question: ").append(question);

            String options = (String) args.get("options");
            if (options != null && !options.isBlank()) {
                sb.append("\n\nOptions:");
                String[] parts = options.split(",");
                for (int i = 0; i < parts.length; i++) {
                    sb.append("\n  ").append(i + 1).append(". ").append(parts[i].trim());
                }
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("type", "clarification_request");
            metadata.put("question", question);
            if (options != null) metadata.put("options", options);

            return Mono.just(ToolResult.success("clarify", sb.toString(), metadata));
        });
    }
}
