package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelInboundHandler;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class FeishuChannelTest {

    @Test
    void startAndStop() {
        FeishuChannel channel = new FeishuChannel("feishu-test", "https://open.feishu.cn/open-apis/bot/v2/hook/fake");
        assertThat(channel.id()).isEqualTo("feishu-test");

        ChannelInboundHandler handler = msg -> Mono.just(ChannelAck.ok());
        channel.start(handler).block();
        channel.stop().block();
    }

    @Test
    void doubleStartThrows() {
        FeishuChannel channel = new FeishuChannel("feishu-test", "https://open.feishu.cn/open-apis/bot/v2/hook/fake");
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
    void injectInboundWithNoHandler() {
        FeishuChannel channel = new FeishuChannel("feishu-test", "https://open.feishu.cn/open-apis/bot/v2/hook/fake");
        ChannelAck ack = channel.injectInbound("user1", "张三", "你好").block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isFalse();
    }

    @Test
    void injectInboundWithHandler() {
        FeishuChannel channel = new FeishuChannel("feishu-test", "https://open.feishu.cn/open-apis/bot/v2/hook/fake");
        AtomicReference<String> received = new AtomicReference<>();
        ChannelInboundHandler handler = msg -> {
            received.set(msg.content());
            return Mono.just(ChannelAck.ok());
        };
        channel.start(handler).block();
        ChannelAck ack = channel.injectInbound("user1", "张三", "飞书消息测试").block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isTrue();
        assertThat(received.get()).isEqualTo("飞书消息测试");
        channel.stop().block();
    }

    @Test
    void senderFailsWhenStopped() {
        FeishuChannel channel = new FeishuChannel("feishu-test", "https://open.feishu.cn/open-apis/bot/v2/hook/fake");
        ChannelAck ack = channel.sender().send(
                io.kairo.api.channel.ChannelMessage.of(
                        io.kairo.api.channel.ChannelIdentity.of("feishu-test", "dest"),
                        "msg")).block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isFalse();
    }

    @Test
    void injectInboundCardWithNoHandler() {
        FeishuChannel channel = new FeishuChannel("feishu-test", "https://open.feishu.cn/open-apis/bot/v2/hook/fake");
        ChannelAck ack = channel.injectInboundCard("user1", "张三", "标题", "内容").block();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isFalse();
    }

    @Test
    void stopAndRestartWorks() {
        FeishuChannel channel = new FeishuChannel("feishu-test", "https://open.feishu.cn/open-apis/bot/v2/hook/fake");
        ChannelInboundHandler handler = msg -> Mono.just(ChannelAck.ok());
        channel.start(handler).block();
        channel.stop().block();
        channel.start(handler).block();
        ChannelAck ack = channel.injectInbound("u1", "李四", "重启").block();
        assertThat(ack.success()).isTrue();
        channel.stop().block();
    }

    @Test
    void stopWhenNotStartedDoesNotThrow() {
        FeishuChannel channel = new FeishuChannel("feishu-test", "https://open.feishu.cn/open-apis/bot/v2/hook/fake");
        channel.stop().block();
    }

    @Test
    void channelIdMatchesConstruction() {
        FeishuChannel channel = new FeishuChannel("custom-feishu", "https://open.feishu.cn/open-apis/bot/v2/hook/fake");
        assertThat(channel.id()).isEqualTo("custom-feishu");
    }
}
