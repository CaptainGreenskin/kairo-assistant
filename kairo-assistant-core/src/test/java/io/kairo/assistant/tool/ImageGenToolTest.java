package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageGenToolTest {

    private final ImageGenTool tool = new ImageGenTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);
    private final ToolContext withKey = new ToolContext("a", "s", Map.of("openaiApiKey", "test-key"));

    @Test
    void promptRequired() {
        ToolResult r = tool.execute(Map.of("size", "1024x1024"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("prompt");
    }

    @Test
    void blankPromptErrors() {
        ToolResult r = tool.execute(Map.of("prompt", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void noApiKeyErrors() {
        ToolResult r = tool.execute(Map.of("prompt", "A cat"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("OPENAI_API_KEY");
    }

    @Test
    void apiKeyFromDependenciesUsed() {
        ToolResult r = tool.execute(Map.of("prompt", "A cat"), withKey).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).doesNotContain("OPENAI_API_KEY");
    }

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema().properties()).containsKey("prompt");
        assertThat(tool.inputSchema().properties()).containsKey("size");
        assertThat(tool.inputSchema().properties()).containsKey("model");
    }

    @Test
    void schemaRequiresPrompt() {
        assertThat(tool.inputSchema().required()).containsExactly("prompt");
    }

    @Test
    void toolAnnotation() {
        var annotation = ImageGenTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("image_gen");
        assertThat(annotation.category()).isEqualTo(io.kairo.api.tool.ToolCategory.EXTERNAL);
    }

    @Test
    void emptyDependenciesNoKey() {
        ToolContext emptyDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of("prompt", "A dog"), emptyDeps).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void blankApiKeyInDependenciesErrors() {
        ToolContext blankKey = new ToolContext("a", "s", Map.of("openaiApiKey", "  "));
        ToolResult r = tool.execute(Map.of("prompt", "A bird"), blankKey).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("OPENAI_API_KEY");
    }

    @Test
    void sizeParameterAccepted() {
        ToolResult r = tool.execute(
                Map.of("prompt", "A landscape", "size", "1792x1024"), withKey).block();
        assertThat(r).isNotNull();
    }

    @Test
    void modelParameterAccepted() {
        ToolResult r = tool.execute(
                Map.of("prompt", "A portrait", "model", "dall-e-2"), withKey).block();
        assertThat(r).isNotNull();
    }

    @Test
    void sideEffectIsWrite() {
        var ann = ImageGenTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }
}
