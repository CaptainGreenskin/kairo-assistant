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
        assertThat(encoded.content()).isEqualTo("aGVsbG8gd29ybGQ=");

        ToolResult decoded = tool.execute(
                Map.of("action", "base64_decode", "input", "aGVsbG8gd29ybGQ="), ctx).block();
        assertThat(decoded.content()).isEqualTo("hello world");
    }

    @Test
    void urlEncodeAndDecode() {
        ToolResult encoded = tool.execute(
                Map.of("action", "url_encode", "input", "hello world&foo=bar"), ctx).block();
        assertThat(encoded.content()).isEqualTo("hello+world%26foo%3Dbar");

        ToolResult decoded = tool.execute(
                Map.of("action", "url_decode", "input", "hello+world%26foo%3Dbar"), ctx).block();
        assertThat(decoded.content()).isEqualTo("hello world&foo=bar");
    }

    @Test
    void md5Hash() {
        ToolResult result = tool.execute(
                Map.of("action", "md5", "input", "hello"), ctx).block();
        assertThat(result.content()).isEqualTo("5d41402abc4b2a76b9719d911017c592");
    }

    @Test
    void sha256Hash() {
        ToolResult result = tool.execute(
                Map.of("action", "sha256", "input", "hello"), ctx).block();
        assertThat(result.content()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void hexRoundTrip() {
        ToolResult encoded = tool.execute(
                Map.of("action", "hex_encode", "input", "abc"), ctx).block();
        assertThat(encoded.content()).isEqualTo("616263");

        ToolResult decoded = tool.execute(
                Map.of("action", "hex_decode", "input", "616263"), ctx).block();
        assertThat(decoded.content()).isEqualTo("abc");
    }

    @Test
    void inputRequired() {
        ToolResult result = tool.execute(Map.of("action", "md5"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("input");
    }

    @Test
    void unknownActionErrors() {
        ToolResult result = tool.execute(
                Map.of("action", "rot13", "input", "hello"), ctx).block();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void base64DecodeInvalidInput() {
        ToolResult result = tool.execute(
                Map.of("action", "base64_decode", "input", "!!!not-base64!!!"), ctx).block();
        assertThat(result.isError()).isTrue();
    }

    @Test
    void schemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("input");
        assertThat(schema.required()).containsExactly("action", "input");
    }

    @Test
    void emptyInputEncodes() {
        ToolResult result = tool.execute(
                Map.of("action", "base64_encode", "input", ""), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEmpty();
    }
}
