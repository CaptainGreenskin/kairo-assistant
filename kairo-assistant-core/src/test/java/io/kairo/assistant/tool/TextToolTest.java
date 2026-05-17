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
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Words: 5");
        assertThat(result.content()).contains("Lines: 2");
    }

    @Test
    void replaceText() {
        ToolResult result = tool.execute(
                Map.of("action", "replace", "text", "hello world",
                        "find", "world", "replacement", "Java"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("hello Java");
    }

    @Test
    void regexMatch() {
        ToolResult result = tool.execute(
                Map.of("action", "regex", "text", "foo123bar456",
                        "find", "\\d+"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("2 matches");
    }

    @Test
    void sortLines() {
        ToolResult result = tool.execute(
                Map.of("action", "sort_lines", "text", "banana\napple\ncherry"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("apple\nbanana\ncherry");
    }

    @Test
    void upperCase() {
        ToolResult result = tool.execute(
                Map.of("action", "upper", "text", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("HELLO");
    }
}
