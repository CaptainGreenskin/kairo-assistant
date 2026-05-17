package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScreenshotToolTest {

    private final ScreenshotTool tool = new ScreenshotTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void hasInputSchema() {
        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.inputSchema().type()).isEqualTo("object");
    }

    @Test
    void schemaContainsOutputAndRegion() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("output");
        assertThat(schema.properties()).containsKey("region");
    }

    @Test
    void defaultOutputPath() {
        ToolResult r = tool.execute(Map.of(), ctx).block();
        assertThat(r).isNotNull();
    }

    @Test
    void customOutputPath() {
        ToolResult r = tool.execute(Map.of("output", "/tmp/test-shot.png"), ctx).block();
        assertThat(r).isNotNull();
    }

    @Test
    void toolAnnotationPresent() {
        var annotation = ScreenshotTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("screenshot");
    }
}
