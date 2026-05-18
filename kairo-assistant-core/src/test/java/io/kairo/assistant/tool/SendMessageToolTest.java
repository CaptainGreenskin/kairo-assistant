package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.channel.Channel;
import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelFailureMode;
import io.kairo.api.channel.ChannelInboundHandler;
import io.kairo.api.channel.ChannelMessage;
import io.kairo.api.channel.ChannelOutboundSender;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
        ToolResult result = tool.execute(Map.of("channel", "  ", "message", "hello"), ctx).block();
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
        ToolResult result = tool.execute(Map.of("channel", "test", "message", "   "), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("message");
    }

    @Test
    void failsWhenNoChannelsConfigured() {
        var ctx = new ToolContext("test", "s1", Map.of());
        ToolResult result = tool.execute(
                Map.of("channel", "slack", "message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("No channels");
    }

    @Test
    void failsWhenChannelsMapIsEmpty() {
        var ctx = ctxWithChannels(Map.of());
        ToolResult result = tool.execute(
                Map.of("channel", "slack", "message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("No channels");
    }

    @Test
    void failsWhenChannelNotFound() {
        var ctx = ctxWithChannels(Map.of("dingtalk", stubChannel(true)));
        ToolResult result = tool.execute(
                Map.of("channel", "slack", "message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Channel not found");
        assertThat(result.content()).contains("Available");
    }

    @Test
    void sendsSuccessfully() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(true)));
        ToolResult result = tool.execute(
                Map.of("channel", "test", "message", "hello world"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("successfully");
    }

    @Test
    void sendWithDestination() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(true)));
        ToolResult result = tool.execute(
                Map.of("channel", "test", "message", "hi", "destination", "group-123"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("successfully");
    }

    @Test
    void reportsChannelFailure() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(false)));
        ToolResult result = tool.execute(
                Map.of("channel", "test", "message", "hello"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Failed to send");
    }

    @Test
    void handlesExceptionFromSender() {
        Channel errorChannel = new Channel() {
            @Override public String id() { return "broken"; }
            @Override public Mono<Void> start(ChannelInboundHandler handler) { return Mono.empty(); }
            @Override public Mono<Void> stop() { return Mono.empty(); }
            @Override public ChannelOutboundSender sender() {
                return msg -> Mono.error(new RuntimeException("connection timeout"));
            }
        };
        var ctx = ctxWithChannels(Map.of("broken", errorChannel));
        ToolResult result = tool.execute(
                Map.of("channel", "broken", "message", "test"), ctx).block();
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
        return new Channel() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public Mono<Void> start(ChannelInboundHandler handler) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> stop() {
                return Mono.empty();
            }

            @Override
            public ChannelOutboundSender sender() {
                return (ChannelMessage msg) -> {
                    if (success) {
                        return Mono.just(ChannelAck.ok());
                    } else {
                        return Mono.just(ChannelAck.fail(
                                ChannelFailureMode.SEND_FAILED, "test failure"));
                    }
                };
            }
        };
    }
}
