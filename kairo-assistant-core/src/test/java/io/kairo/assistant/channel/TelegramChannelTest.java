package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelInboundHandler;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TelegramChannelTest {

    @Test
    void startAndStopLifecycle() {
        TelegramChannel channel = new TelegramChannel("tg-test", "fake-token");
        assertThat(channel.id()).isEqualTo("tg-test");

        ChannelInboundHandler handler = msg -> Mono.just(ChannelAck.ok());
        channel.start(handler).block();
        channel.stop().block();
    }

    @Test
    void senderFailsWhenNotRunning() {
        TelegramChannel channel = new TelegramChannel("tg-test", "fake-token");
        var sender = channel.sender();
        ChannelAck ack = sender.send(
                io.kairo.api.channel.ChannelMessage.of(
                        io.kairo.api.channel.ChannelIdentity.of("tg-test", "123"),
                        "hello")).block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isFalse();
    }

    @Test
    void doubleStartThrows() {
        TelegramChannel channel = new TelegramChannel("tg-test", "fake-token");
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
    void channelIdMatchesConstruction() {
        TelegramChannel channel = new TelegramChannel("custom-id", "token");
        assertThat(channel.id()).isEqualTo("custom-id");
    }

    @Test
    void stopWhenNotStartedDoesNotThrow() {
        TelegramChannel channel = new TelegramChannel("tg-test", "fake-token");
        channel.stop().block();
    }

    @Test
    void stopAndRestartWorks() {
        TelegramChannel channel = new TelegramChannel("tg-test", "fake-token");
        ChannelInboundHandler handler = msg -> Mono.just(ChannelAck.ok());
        channel.start(handler).block();
        channel.stop().block();
        channel.start(handler).block();
        channel.stop().block();
    }
}
