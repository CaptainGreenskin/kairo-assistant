package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonToolTest {

    private final JsonTool tool = new JsonTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void validateValidJson() {
        ToolResult result = tool.execute(
                Map.of("action", "validate", "json", "{\"a\":1}"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Valid");
    }

    @Test
    void validateInvalidJson() {
        ToolResult result = tool.execute(
                Map.of("action", "validate", "json", "{bad}"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void queryJsonPointer() {
        ToolResult result = tool.execute(
                Map.of("action", "query",
                        "json", "{\"data\":[{\"name\":\"alice\"}]}",
                        "pointer", "/data/0/name"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("alice");
    }

    @Test
    void formatPrettyPrint() {
        ToolResult result = tool.execute(
                Map.of("action", "format", "json", "{\"a\":1,\"b\":2}"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("\n");
    }

    @Test
    void minify() {
        ToolResult result = tool.execute(
                Map.of("action", "minify", "json", "{ \"a\" : 1 }"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("{\"a\":1}");
    }
}
