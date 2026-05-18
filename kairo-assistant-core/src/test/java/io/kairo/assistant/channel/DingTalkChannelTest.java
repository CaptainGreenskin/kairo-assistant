package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class DingTalkChannelTest {

    @Test
    void idReturnsConfiguredId() {
        DingTalkChannel channel = new DingTalkChannel("dingtalk-test", "https://example.com/hook");
        assertThat(channel.id()).isEqualTo("dingtalk-test");
    }

    @Test
    void startAndStopLifecycle() {
        DingTalkChannel channel = new DingTalkChannel("dingtalk-test", "https://example.com/hook");
        channel.start(msg -> Mono.just(ChannelAck.ok())).block();
        channel.stop().block();
    }

    @Test
    void doubleStartThrows() {
        DingTalkChannel channel = new DingTalkChannel("dingtalk-test", "https://example.com/hook");
        channel.start(msg -> Mono.just(ChannelAck.ok())).block();

        assertThatThrownBy(
                        () -> channel.start(msg -> Mono.just(ChannelAck.ok())).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    @Test
    void senderRejectsWhenNotRunning() {
        DingTalkChannel channel = new DingTalkChannel("dingtalk-test", "https://example.com/hook");
        ChannelMessage msg =
                ChannelMessage.of(
                        io.kairo.api.channel.ChannelIdentity.of("dingtalk-test", "user1"),
                        "hello");
        ChannelAck ack = channel.sender().send(msg).block();
        assertThat(ack.success()).isFalse();
        assertThat(ack.detail()).contains("not running");
    }

    @Test
    void injectInboundWithNoHandlerFails() {
        DingTalkChannel channel = new DingTalkChannel("dingtalk-test", "https://example.com/hook");
        ChannelAck ack = channel.injectInbound("user1", "Alice", "hello").block();
        assertThat(ack.success()).isFalse();
    }

    @Test
    void injectInboundRoutesToHandler() {
        DingTalkChannel channel = new DingTalkChannel("dingtalk-test", "https://example.com/hook");
        channel.start(msg -> {
            assertThat(msg.content()).isEqualTo("hello");
            assertThat(msg.identity().destination()).isEqualTo("user1");
            assertThat(msg.identity().attributes().get("senderName")).isEqualTo("Alice");
            return Mono.just(ChannelAck.ok());
        }).block();

        ChannelAck ack = channel.injectInbound("user1", "Alice", "hello").block();
        assertThat(ack.success()).isTrue();
    }

    @Test
    void stopAndRestartWorks() {
        DingTalkChannel channel = new DingTalkChannel("dingtalk-test", "https://example.com/hook");
        channel.start(msg -> Mono.just(ChannelAck.ok())).block();
        channel.stop().block();
        channel.start(msg -> Mono.just(ChannelAck.ok())).block();
        ChannelAck ack = channel.injectInbound("user1", "Bob", "restart test").block();
        assertThat(ack.success()).isTrue();
        channel.stop().block();
    }

    @Test
    void stopWhenNotStartedDoesNotThrow() {
        DingTalkChannel channel = new DingTalkChannel("dingtalk-test", "https://example.com/hook");
        channel.stop().block();
    }

    @Test
    void channelIdMatchesConstruction() {
        DingTalkChannel channel = new DingTalkChannel("custom-dt-id", "https://example.com/hook");
        assertThat(channel.id()).isEqualTo("custom-dt-id");
    }
}
