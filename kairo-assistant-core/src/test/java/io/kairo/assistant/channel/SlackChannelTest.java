package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelInboundHandler;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SlackChannelTest {

    @Test
    void startAndStop() {
        SlackChannel channel = new SlackChannel("slack-test", "xoxb-fake");
        assertThat(channel.id()).isEqualTo("slack-test");

        ChannelInboundHandler handler = msg -> Mono.just(ChannelAck.ok());
        channel.start(handler).block();
        channel.stop().block();
    }

    @Test
    void injectInboundWithNoHandler() {
        SlackChannel channel = new SlackChannel("slack-test", "xoxb-fake");
        ChannelAck ack = channel.injectInbound("C123", "U456", "bob", "hello").block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isFalse();
    }

    @Test
    void injectInboundWithHandler() {
        SlackChannel channel = new SlackChannel("slack-test", "xoxb-fake");
        AtomicReference<String> received = new AtomicReference<>();
        ChannelInboundHandler handler = msg -> {
            received.set(msg.content());
            return Mono.just(ChannelAck.ok());
        };
        channel.start(handler).block();
        ChannelAck ack = channel.injectInbound("C123", "U456", "bob", "hello world").block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isTrue();
        assertThat(received.get()).isEqualTo("hello world");
        channel.stop().block();
    }

    @Test
    void senderFailsWhenNotRunning() {
        SlackChannel channel = new SlackChannel("slack-test", "xoxb-fake");
        var sender = channel.sender();
        var msg = io.kairo.api.channel.ChannelMessage.of(
                io.kairo.api.channel.ChannelIdentity.of("slack-test", "C123"), "hi");
        ChannelAck ack = sender.send(msg).block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isFalse();
    }

    @Test
    void doubleStartThrows() {
        SlackChannel channel = new SlackChannel("slack-test", "xoxb-fake");
        ChannelInboundHandler handler = msg -> Mono.just(ChannelAck.ok());
        channel.start(handler).block();
        try {
            channel.start(handler).block();
            assertThat(true).as("should have thrown").isFalse();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("already started");
        } finally {
            channel.stop().block();
        }
    }

    @Test
    void stopAndRestartWorks() {
        SlackChannel channel = new SlackChannel("slack-test", "xoxb-fake");
        ChannelInboundHandler handler = msg -> Mono.just(ChannelAck.ok());
        channel.start(handler).block();
        channel.stop().block();
        channel.start(handler).block();
        ChannelAck ack = channel.injectInbound("C123", "U456", "bob", "after restart").block();
        assertThat(ack.success()).isTrue();
        channel.stop().block();
    }
}
