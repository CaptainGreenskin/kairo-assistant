package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VoiceToolTest {

    private final VoiceTool tool = new VoiceTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of("text", "hello"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void noApiKeyErrors() {
        ToolResult r = tool.execute(Map.of("action", "tts", "text", "hello"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("OPENAI_API_KEY");
    }

    @Test
    void sttRequiresFile() {
        ToolContext withKey = new ToolContext("a", "s", Map.of("openaiApiKey", "test-key"));
        ToolResult r = tool.execute(Map.of("action", "stt"), withKey).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void ttsRequiresText() {
        ToolContext withKey = new ToolContext("a", "s", Map.of("openaiApiKey", "test-key"));
        ToolResult r = tool.execute(Map.of("action", "tts"), withKey).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void unknownActionErrors() {
        ToolContext withKey = new ToolContext("a", "s", Map.of("openaiApiKey", "test-key"));
        ToolResult r = tool.execute(Map.of("action", "sing"), withKey).block();
        assertThat(r.isError()).isTrue();
    }
}
