package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageGenToolTest {

    private final ImageGenTool tool = new ImageGenTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void promptRequired() {
        ToolResult r = tool.execute(Map.of("size", "1024x1024"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void noApiKeyErrors() {
        ToolResult r = tool.execute(Map.of("prompt", "A cat"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("OPENAI_API_KEY");
    }

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema().properties()).containsKey("prompt");
    }
}
