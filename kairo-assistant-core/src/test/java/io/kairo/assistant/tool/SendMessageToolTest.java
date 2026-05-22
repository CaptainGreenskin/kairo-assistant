/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.gateway.Channel;
import io.kairo.api.gateway.ChannelMessage;
import io.kairo.api.gateway.DeliveryTarget;
import io.kairo.api.gateway.PlatformCapabilities;
import io.kairo.api.gateway.SendResult;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SendMessageToolTest {

    private final SendMessageTool tool = new SendMessageTool();

    @Test
    void requiresChannel() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(true)));
        ToolResult result = tool.execute(Map.of("message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("channel");
    }

    @Test
    void blankChannelErrors() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(true)));
        ToolResult result =
                tool.execute(Map.of("channel", "  ", "message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("channel");
    }

    @Test
    void requiresMessage() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(true)));
        ToolResult result = tool.execute(Map.of("channel", "test"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("message");
    }

    @Test
    void blankMessageErrors() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(true)));
        ToolResult result =
                tool.execute(Map.of("channel", "test", "message", "   "), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("message");
    }

    @Test
    void failsWhenNoChannelsConfigured() {
        var ctx = new ToolContext("test", "s1", Map.of());
        ToolResult result =
                tool.execute(Map.of("channel", "slack", "message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("No channels");
    }

    @Test
    void failsWhenChannelsMapIsEmpty() {
        var ctx = ctxWithChannels(Map.of());
        ToolResult result =
                tool.execute(Map.of("channel", "slack", "message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("No channels");
    }

    @Test
    void failsWhenChannelNotFound() {
        var ctx = ctxWithChannels(Map.of("dingtalk", stubChannel(true)));
        ToolResult result =
                tool.execute(Map.of("channel", "slack", "message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Channel not found");
        assertThat(result.content()).contains("Available");
    }

    @Test
    void sendsSuccessfully() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(true)));
        ToolResult result =
                tool.execute(Map.of("channel", "test", "message", "hello world"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("successfully");
    }

    @Test
    void sendWithDestination() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(true)));
        ToolResult result =
                tool.execute(
                                Map.of(
                                        "channel",
                                        "test",
                                        "message",
                                        "hi",
                                        "destination",
                                        "group-123"),
                                ctx)
                        .block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("successfully");
    }

    @Test
    void reportsChannelFailure() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(false)));
        ToolResult result =
                tool.execute(Map.of("channel", "test", "message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Failed to send");
    }

    @Test
    void handlesExceptionFromSender() {
        Channel errorChannel = new StubChannel("broken", () -> Mono.error(new RuntimeException("connection timeout")));
        var ctx = ctxWithChannels(Map.of("broken", errorChannel));
        ToolResult result =
                tool.execute(Map.of("channel", "broken", "message", "test"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("connection timeout");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("channel", "message");
        assertThat(schema.properties()).containsKey("destination");
    }

    @Test
    void toolAnnotation() {
        var ann = SendMessageTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("send_message");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.EXTERNAL);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.SYSTEM_CHANGE);
    }

    private ToolContext ctxWithChannels(Map<String, Channel> channels) {
        return new ToolContext("test", "s1", Map.of("channels", channels));
    }

    private Channel stubChannel(boolean success) {
        return new StubChannel(
                "test",
                () ->
                        Mono.just(
                                success
                                        ? SendResult.ok("ok")
                                        : SendResult.fail(
                                                SendResult.FailureMode.TRANSIENT,
                                                "test failure")));
    }

    /** Minimal Channel stub for testing — implements only the mandatory contract. */
    private static final class StubChannel implements Channel {
        private final String id;
        private final java.util.function.Supplier<Mono<SendResult>> sender;

        StubChannel(String id, java.util.function.Supplier<Mono<SendResult>> sender) {
            this.id = id;
            this.sender = sender;
        }

        @Override public String id() { return id; }
        @Override public PlatformCapabilities capabilities() { return PlatformCapabilities.textOnly(); }
        @Override public Mono<Void> connect() { return Mono.empty(); }
        @Override public Mono<Void> disconnect() { return Mono.empty(); }
        @Override public Flux<ChannelMessage> inbound() { return Flux.empty(); }

        @Override
        public Mono<SendResult> send(
                DeliveryTarget target,
                String content,
                String replyToMessageId,
                Map<String, Object> metadata) {
            return sender.get();
        }
    }
}
