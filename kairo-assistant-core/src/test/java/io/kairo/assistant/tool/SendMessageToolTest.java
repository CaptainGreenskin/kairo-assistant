package io.kairo.assistant.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.channel.Channel;
import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelFailureMode;
import io.kairo.api.channel.ChannelInboundHandler;
import io.kairo.api.channel.ChannelMessage;
import io.kairo.api.channel.ChannelOutboundSender;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SendMessageToolTest {

    private SendMessageTool tool;

    @BeforeEach
    void setUp() {
        tool = new SendMessageTool();
    }

    @Test
    void requiresChannel() {
        var ctx = ctxWithChannels(Map.of());
        ToolResult result = tool.execute(Map.of("message", "hello"), ctx).block();
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void requiresMessage() {
        var ctx = ctxWithChannels(Map.of());
        ToolResult result = tool.execute(Map.of("channel", "test"), ctx).block();
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void failsWhenNoChannelsConfigured() {
        var ctx = new ToolContext("test", "s1", Map.of());
        ToolResult result = tool.execute(
                Map.of("channel", "slack", "message", "hello"), ctx).block();
        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.content().contains("No channels"));
    }

    @Test
    void failsWhenChannelNotFound() {
        var ctx = ctxWithChannels(Map.of("dingtalk", stubChannel(true)));
        ToolResult result = tool.execute(
                Map.of("channel", "slack", "message", "hello"), ctx).block();
        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Channel not found"));
    }

    @Test
    void sendsSuccessfully() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(true)));
        ToolResult result = tool.execute(
                Map.of("channel", "test", "message", "hello world"), ctx).block();
        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().contains("successfully"));
    }

    @Test
    void reportsChannelFailure() {
        var ctx = ctxWithChannels(Map.of("test", stubChannel(false)));
        ToolResult result = tool.execute(
                Map.of("channel", "test", "message", "hello"), ctx).block();
        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Failed to send"));
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
