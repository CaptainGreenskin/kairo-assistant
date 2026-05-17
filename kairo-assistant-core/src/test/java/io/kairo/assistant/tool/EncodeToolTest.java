package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EncodeToolTest {

    private final EncodeTool tool = new EncodeTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void base64RoundTrip() {
        ToolResult encoded = tool.execute(
                Map.of("action", "base64_encode", "input", "hello world"), ctx).block();
        assertThat(encoded).isNotNull();
        assertThat(encoded.content()).isEqualTo("aGVsbG8gd29ybGQ=");

        ToolResult decoded = tool.execute(
                Map.of("action", "base64_decode", "input", "aGVsbG8gd29ybGQ="), ctx).block();
        assertThat(decoded).isNotNull();
        assertThat(decoded.content()).isEqualTo("hello world");
    }

    @Test
    void urlEncode() {
        ToolResult result = tool.execute(
                Map.of("action", "url_encode", "input", "hello world&foo=bar"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("hello+world%26foo%3Dbar");
    }

    @Test
    void md5Hash() {
        ToolResult result = tool.execute(
                Map.of("action", "md5", "input", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("5d41402abc4b2a76b9719d911017c592");
    }

    @Test
    void sha256Hash() {
        ToolResult result = tool.execute(
                Map.of("action", "sha256", "input", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }
}
