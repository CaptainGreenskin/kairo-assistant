package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextToolTest {

    private final TextTool tool = new TextTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void countWordsAndLines() {
        ToolResult result = tool.execute(
                Map.of("action", "count", "text", "hello world\nfoo bar baz"), ctx).block();
        assertThat(result.content()).contains("Words: 5");
        assertThat(result.content()).contains("Lines: 2");
    }

    @Test
    void countEmptyText() {
        ToolResult result = tool.execute(
                Map.of("action", "count", "text", ""), ctx).block();
        assertThat(result.content()).contains("Words: 0");
        assertThat(result.content()).contains("Characters: 0");
    }

    @Test
    void replaceText() {
        ToolResult result = tool.execute(
                Map.of("action", "replace", "text", "hello world",
                        "find", "world", "replacement", "Java"), ctx).block();
        assertThat(result.content()).isEqualTo("hello Java");
    }

    @Test
    void replaceRequiresFind() {
        ToolResult result = tool.execute(
                Map.of("action", "replace", "text", "hello"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("find");
    }

    @Test
    void replaceWithoutReplacement() {
        ToolResult result = tool.execute(
                Map.of("action", "replace", "text", "hello world", "find", " world"), ctx).block();
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void regexMatch() {
        ToolResult result = tool.execute(
                Map.of("action", "regex", "text", "foo123bar456",
                        "find", "\\d+"), ctx).block();
        assertThat(result.content()).contains("2 matches");
    }

    @Test
    void regexNoMatch() {
        ToolResult result = tool.execute(
                Map.of("action", "regex", "text", "abcdef",
                        "find", "\\d+"), ctx).block();
        assertThat(result.content()).contains("No matches");
    }

    @Test
    void regexRequiresFind() {
        ToolResult result = tool.execute(
                Map.of("action", "regex", "text", "hello"), ctx).block();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void sortLines() {
        ToolResult result = tool.execute(
                Map.of("action", "sort_lines", "text", "banana\napple\ncherry"), ctx).block();
        assertThat(result.content()).isEqualTo("apple\nbanana\ncherry");
    }

    @Test
    void uniqueLines() {
        ToolResult result = tool.execute(
                Map.of("action", "unique_lines", "text", "a\nb\na\nc\nb"), ctx).block();
        assertThat(result.content()).isEqualTo("a\nb\nc");
    }

    @Test
    void upperCase() {
        ToolResult result = tool.execute(
                Map.of("action", "upper", "text", "hello"), ctx).block();
        assertThat(result.content()).isEqualTo("HELLO");
    }

    @Test
    void lowerCase() {
        ToolResult result = tool.execute(
                Map.of("action", "lower", "text", "HELLO"), ctx).block();
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void titleCase() {
        ToolResult result = tool.execute(
                Map.of("action", "title", "text", "hello world"), ctx).block();
        assertThat(result.content()).isEqualTo("Hello World");
    }

    @Test
    void trimWhitespace() {
        ToolResult result = tool.execute(
                Map.of("action", "trim", "text", "  hello  "), ctx).block();
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void reverse() {
        ToolResult result = tool.execute(
                Map.of("action", "reverse", "text", "abc"), ctx).block();
        assertThat(result.content()).isEqualTo("cba");
    }

    @Test
    void textRequired() {
        ToolResult result = tool.execute(Map.of("action", "count"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("text");
    }

    @Test
    void unknownActionErrors() {
        ToolResult result = tool.execute(
                Map.of("action", "encrypt", "text", "hello"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown action");
    }

    @Test
    void schemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("text");
        assertThat(schema.properties()).containsKey("find");
        assertThat(schema.properties()).containsKey("replacement");
        assertThat(schema.required()).containsExactly("action", "text");
    }
}
