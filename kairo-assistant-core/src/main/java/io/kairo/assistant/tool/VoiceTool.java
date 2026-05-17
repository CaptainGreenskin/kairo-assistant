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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "voice",
        description =
                "Text-to-speech: convert text to audio file using OpenAI TTS API. "
                        + "Speech-to-text: transcribe audio file using Whisper API.",
        category = ToolCategory.EXTERNAL,
        sideEffect = ToolSideEffect.WRITE)
public class VoiceTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "'tts' for text-to-speech, 'stt' for speech-to-text."));
        props.put("text", new JsonSchema("string", null, null,
                "Text to convert to speech (for tts action)."));
        props.put("file", new JsonSchema("string", null, null,
                "Audio file path to transcribe (for stt action), or output path (for tts)."));
        props.put("voice", new JsonSchema("string", null, null,
                "TTS voice: alloy, echo, fable, onyx, nova, shimmer. Default: alloy."));
        props.put("model", new JsonSchema("string", null, null,
                "Model: 'tts-1', 'tts-1-hd', 'whisper-1'. Default: tts-1 for tts, whisper-1 for stt."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args, ctx));
    }

    private ToolResult doExecute(Map<String, Object> args, ToolContext ctx) {
        String action = (String) args.get("action");
        if (action == null || action.isBlank()) {
            return ToolResult.error("voice", "'action' required (tts or stt)");
        }

        String apiKey = resolveOpenAIKey(ctx);
        if (apiKey == null || apiKey.isBlank()) {
            return ToolResult.error("voice", "OPENAI_API_KEY not configured.");
        }

        return switch (action) {
            case "tts" -> doTts(args, apiKey);
            case "stt" -> doStt(args, apiKey);
            default -> ToolResult.error("voice", "Unknown action: " + action);
        };
    }

    private ToolResult doTts(Map<String, Object> args, String apiKey) {
        String text = (String) args.get("text");
        if (text == null || text.isBlank()) {
            return ToolResult.error("voice", "'text' required for tts");
        }

        String voice = args.get("voice") instanceof String v ? v : "alloy";
        String model = args.get("model") instanceof String m ? m : "tts-1";
        String outputFile = args.get("file") instanceof String f ? f : "/tmp/kairo-tts-output.mp3";

        try {
            String requestBody = String.format(
                    """
                    {"model":"%s","input":"%s","voice":"%s"}
                    """,
                    model,
                    text.replace("\"", "\\\"").replace("\n", " "),
                    voice);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/audio/speech"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<byte[]> resp =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() == 200) {
                Files.write(Path.of(outputFile), resp.body());
                return ToolResult.success("voice",
                        "Audio saved to: " + outputFile + " (" + resp.body().length + " bytes)");
            }
            return ToolResult.error("voice",
                    "TTS API error (HTTP " + resp.statusCode() + "): "
                            + new String(resp.body()));
        } catch (Exception e) {
            return ToolResult.error("voice", "TTS failed: " + e.getMessage());
        }
    }

    private ToolResult doStt(Map<String, Object> args, String apiKey) {
        String file = (String) args.get("file");
        if (file == null || file.isBlank()) {
            return ToolResult.error("voice", "'file' required for stt");
        }

        Path audioPath = Path.of(file);
        if (!Files.exists(audioPath)) {
            return ToolResult.error("voice", "Audio file not found: " + file);
        }

        try {
            String boundary = "----KairoVoiceBoundary" + System.currentTimeMillis();
            byte[] audioBytes = Files.readAllBytes(audioPath);
            String fileName = audioPath.getFileName().toString();

            String prefix = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + fileName + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            String suffix = "\r\n--" + boundary + "--\r\n";

            byte[] prefixBytes = prefix.getBytes();
            byte[] suffixBytes = suffix.getBytes();
            byte[] body = new byte[prefixBytes.length + audioBytes.length + suffixBytes.length];
            System.arraycopy(prefixBytes, 0, body, 0, prefixBytes.length);
            System.arraycopy(audioBytes, 0, body, prefixBytes.length, audioBytes.length);
            System.arraycopy(suffixBytes, 0, body, prefixBytes.length + audioBytes.length,
                    suffixBytes.length);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/audio/transcriptions"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> resp =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                return ToolResult.success("voice", "Transcription:\n" + resp.body());
            }
            return ToolResult.error("voice",
                    "STT API error (HTTP " + resp.statusCode() + "): " + resp.body());
        } catch (Exception e) {
            return ToolResult.error("voice", "STT failed: " + e.getMessage());
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
