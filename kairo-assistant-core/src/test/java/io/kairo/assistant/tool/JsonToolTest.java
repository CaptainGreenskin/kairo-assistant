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
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("Valid");
    }

    @Test
    void validateInvalidJson() {
        ToolResult result = tool.execute(
                Map.of("action", "validate", "json", "{bad}"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Invalid JSON");
    }

    @Test
    void queryJsonPointer() {
        ToolResult result = tool.execute(
                Map.of("action", "query",
                        "json", "{\"data\":[{\"name\":\"alice\"}]}",
                        "pointer", "/data/0/name"), ctx).block();
        assertThat(result.content()).contains("alice");
    }

    @Test
    void queryMissingPointerErrors() {
        ToolResult result = tool.execute(
                Map.of("action", "query", "json", "{\"a\":1}"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("pointer");
    }

    @Test
    void queryNonexistentPath() {
        ToolResult result = tool.execute(
                Map.of("action", "query", "json", "{\"a\":1}", "pointer", "/b/c"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("No value");
    }

    @Test
    void formatPrettyPrint() {
        ToolResult result = tool.execute(
                Map.of("action", "format", "json", "{\"a\":1,\"b\":2}"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("\n");
    }

    @Test
    void formatInvalidJsonErrors() {
        ToolResult result = tool.execute(
                Map.of("action", "format", "json", "not json"), ctx).block();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void minify() {
        ToolResult result = tool.execute(
                Map.of("action", "minify", "json", "{ \"a\" : 1 }"), ctx).block();
        assertThat(result.content()).isEqualTo("{\"a\":1}");
    }

    @Test
    void minifyArray() {
        ToolResult result = tool.execute(
                Map.of("action", "minify", "json", "[ 1 , 2 , 3 ]"), ctx).block();
        assertThat(result.content()).isEqualTo("[1,2,3]");
    }

    @Test
    void jsonRequired() {
        ToolResult result = tool.execute(Map.of("action", "validate"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("json");
    }

    @Test
    void blankJsonErrors() {
        ToolResult result = tool.execute(Map.of("action", "validate", "json", "  "), ctx).block();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void unknownActionErrors() {
        ToolResult result = tool.execute(
                Map.of("action", "transform", "json", "{}"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown action");
    }

    @Test
    void schemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("json");
        assertThat(schema.properties()).containsKey("pointer");
        assertThat(schema.required()).containsExactly("action", "json");
    }

    @Test
    void validateJsonArray() {
        ToolResult result = tool.execute(
                Map.of("action", "validate", "json", "[1,2,3]"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("Valid");
    }
}
