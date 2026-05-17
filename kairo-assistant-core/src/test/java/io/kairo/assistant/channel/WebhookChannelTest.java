package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelInboundHandler;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class WebhookChannelTest {

    @Test
    void startAndStop() {
        WebhookChannel channel = new WebhookChannel("wh-test", "http://localhost:9999/webhook");
        assertThat(channel.id()).isEqualTo("wh-test");

        ChannelInboundHandler handler = msg -> Mono.just(ChannelAck.ok());
        channel.start(handler).block();
        channel.stop().block();
    }

    @Test
    void injectInboundWithNoHandler() {
        WebhookChannel channel = new WebhookChannel("wh-test", "http://localhost:9999/webhook");
        ChannelAck ack = channel.injectInbound("sender1", "test message").block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isFalse();
    }

    @Test
    void injectInboundWithHandler() {
        WebhookChannel channel = new WebhookChannel("wh-test", "http://localhost:9999/webhook");
        AtomicReference<String> received = new AtomicReference<>();
        ChannelInboundHandler handler = msg -> {
            received.set(msg.content());
            return Mono.just(ChannelAck.ok());
        };
        channel.start(handler).block();
        ChannelAck ack = channel.injectInbound("sender1", "webhook test").block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isTrue();
        assertThat(received.get()).isEqualTo("webhook test");
        channel.stop().block();
    }

    @Test
    void senderFailsWhenStopped() {
        WebhookChannel channel = new WebhookChannel("wh-test", "http://localhost:9999/webhook");
        ChannelAck ack = channel.sender().send(
                io.kairo.api.channel.ChannelMessage.of(
                        io.kairo.api.channel.ChannelIdentity.of("wh-test", "dest"),
                        "msg")).block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isFalse();
    }
}
