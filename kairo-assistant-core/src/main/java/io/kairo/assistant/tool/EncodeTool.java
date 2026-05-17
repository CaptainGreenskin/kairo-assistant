package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "encode",
        description =
                "Encoding, decoding, and hashing utilities. "
                        + "Actions: base64_encode, base64_decode, url_encode, url_decode, "
                        + "md5, sha256, hex_encode, hex_decode.",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.READ_ONLY)
public class EncodeTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: base64_encode, base64_decode, url_encode, url_decode, md5, sha256, hex_encode, hex_decode."));
        props.put("input", new JsonSchema("string", null, null,
                "Input text to process."));
        return new JsonSchema("object", props, List.of("action", "input"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        String input = (String) args.get("input");
        if (input == null) {
            return ToolResult.error("encode", "'input' required");
        }

        try {
            String result = switch (action.toLowerCase()) {
                case "base64_encode" ->
                        Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
                case "base64_decode" ->
                        new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8);
                case "url_encode" ->
                        URLEncoder.encode(input, StandardCharsets.UTF_8);
                case "url_decode" ->
                        URLDecoder.decode(input, StandardCharsets.UTF_8);
                case "md5" -> hash(input, "MD5");
                case "sha256" -> hash(input, "SHA-256");
                case "hex_encode" ->
                        HexFormat.of().formatHex(input.getBytes(StandardCharsets.UTF_8));
                case "hex_decode" ->
                        new String(HexFormat.of().parseHex(input), StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            };
            return ToolResult.success("encode", result);
        } catch (Exception e) {
            return ToolResult.error("encode", "Failed: " + e.getMessage());
        }
    }

    private String hash(String input, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
