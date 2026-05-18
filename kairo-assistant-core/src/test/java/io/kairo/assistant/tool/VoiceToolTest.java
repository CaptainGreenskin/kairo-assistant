package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceToolTest {

    private final VoiceTool tool = new VoiceTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);
    private final ToolContext withKey = new ToolContext("a", "s", Map.of("openaiApiKey", "test-key"));

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of("text", "hello"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void blankActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("action");
    }

    @Test
    void noApiKeyErrors() {
        ToolResult r = tool.execute(Map.of("action", "tts", "text", "hello"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("OPENAI_API_KEY");
    }

    @Test
    void sttRequiresFile() {
        ToolResult r = tool.execute(Map.of("action", "stt"), withKey).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("file");
    }

    @Test
    void sttBlankFileErrors() {
        ToolResult r = tool.execute(Map.of("action", "stt", "file", "  "), withKey).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void sttFileNotFound() {
        ToolResult r = tool.execute(Map.of("action", "stt", "file", "/tmp/nonexistent_audio_xyz.mp3"), withKey).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("not found");
    }

    @Test
    void ttsRequiresText() {
        ToolResult r = tool.execute(Map.of("action", "tts"), withKey).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("text");
    }

    @Test
    void ttsBlankTextErrors() {
        ToolResult r = tool.execute(Map.of("action", "tts", "text", "  "), withKey).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "sing"), withKey).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void schemaHasAllFields() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("text");
        assertThat(schema.properties()).containsKey("file");
        assertThat(schema.properties()).containsKey("voice");
        assertThat(schema.properties()).containsKey("model");
        assertThat(schema.required()).containsExactly("action");
    }

    @Test
    void apiKeyResolvedFromDependencies() {
        ToolResult r = tool.execute(Map.of("action", "tts", "text", "hello"), withKey).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).doesNotContain("OPENAI_API_KEY");
    }

    @Test
    void sttWithRealFile(@TempDir Path dir) throws IOException {
        Path audio = dir.resolve("fake-audio.mp3");
        Files.writeString(audio, "not-real-audio");
        ToolResult r = tool.execute(Map.of("action", "stt", "file", audio.toString()), withKey).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).doesNotContain("not found");
    }

    @Test
    void toolAnnotation() {
        var annotation = VoiceTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("voice");
        assertThat(annotation.category()).isEqualTo(io.kairo.api.tool.ToolCategory.EXTERNAL);
    }
}
