package io.kairo.assistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.model.ModelProvider;
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
import reactor.core.publisher.Mono;

@Tool(
        name = "image_gen",
        description =
                "Generate images from text descriptions using AI models (DALL-E, etc.). "
                        + "Returns the URL of the generated image.",
        category = ToolCategory.EXTERNAL,
        sideEffect = ToolSideEffect.WRITE)
public class ImageGenTool implements SyncTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("prompt", new JsonSchema("string", null, null,
                "Text description of the image to generate."));
        props.put("size", new JsonSchema("string", null, null,
                "Image size: '1024x1024', '1024x1792', '1792x1024'. Default: '1024x1024'."));
        props.put("model", new JsonSchema("string", null, null,
                "Model to use: 'dall-e-3', 'dall-e-2'. Default: 'dall-e-3'."));
        return new JsonSchema("object", props, List.of("prompt"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args, ctx));
    }

    private ToolResult doExecute(Map<String, Object> args, ToolContext ctx) {
        String prompt = (String) args.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.error("image_gen", "'prompt' required");
        }

        String size = args.get("size") instanceof String s ? s : "1024x1024";
        String model = args.get("model") instanceof String s ? s : "dall-e-3";

        String apiKey = resolveOpenAIKey(ctx);
        if (apiKey == null || apiKey.isBlank()) {
            return ToolResult.error("image_gen",
                    "OPENAI_API_KEY not configured. Set env var or pass via tool dependencies.");
        }

        try {
            Map<String, Object> reqMap = Map.of(
                    "model", model, "prompt", prompt, "n", 1,
                    "size", size, "response_format", "url");
            String requestBody = MAPPER.writeValueAsString(reqMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/images/generations"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> resp = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode root = MAPPER.readTree(resp.body());
                JsonNode data = root.path("data");
                if (data.isArray() && !data.isEmpty()) {
                    String imageUrl = data.get(0).path("url").asText("");
                    if (!imageUrl.isEmpty()) {
                        return ToolResult.success("image_gen", "Image generated: " + imageUrl);
                    }
                }
                return ToolResult.success("image_gen", "Response: " + resp.body());
            }
            return ToolResult.error("image_gen",
                    "API error (HTTP " + resp.statusCode() + "): " + resp.body());
        } catch (Exception e) {
            return ToolResult.error("image_gen", "Failed: " + e.getMessage());
        }
    }

    private String resolveOpenAIKey(ToolContext ctx) {
        if (ctx.dependencies() != null) {
            Object key = ctx.dependencies().get("openaiApiKey");
            if (key instanceof String s && !s.isBlank()) return s;
        }
        return System.getenv("OPENAI_API_KEY");
    }
}
