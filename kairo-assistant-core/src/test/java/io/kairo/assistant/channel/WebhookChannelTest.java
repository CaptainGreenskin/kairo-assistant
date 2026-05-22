/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.gateway.ChannelMessage;
import io.kairo.api.gateway.DeliveryTarget;
import io.kairo.api.gateway.SendResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

class WebhookChannelTest {

    @Test
    void idAndCapabilities() {
        var ch = new WebhookChannel("hook", "http://127.0.0.1:1/x");
        assertThat(ch.id()).isEqualTo("hook");
        assertThat(ch.capabilities()).isNotNull();
    }

    @Test
    void connectFlipsRunning() {
        var ch = new WebhookChannel("hook", "http://127.0.0.1:1/x");
        assertThat(ch.isRunning()).isFalse();
        ch.connect().block();
        assertThat(ch.isRunning()).isTrue();
        ch.disconnect().block();
        assertThat(ch.isRunning()).isFalse();
    }

    @Test
    void injectInboundEmitsOnFlux() throws Exception {
        var ch = new WebhookChannel("hook", "http://127.0.0.1:1/x");
        ch.connect().block();
        List<ChannelMessage> got = new CopyOnWriteArrayList<>();
        Disposable sub = ch.inbound().subscribe(got::add);
        ch.injectInbound("user-1", "hello");
        Thread.sleep(30);
        assertThat(got).hasSize(1);
        assertThat(got.get(0).text()).isEqualTo("hello");
        assertThat(got.get(0).source().channelId()).isEqualTo("hook");
        sub.dispose();
        ch.disconnect().block();
    }

    @Test
    void sendBeforeConnectFails() {
        var ch = new WebhookChannel("hook", "http://127.0.0.1:1/x");
        SendResult r = ch.send(DeliveryTarget.chat("hook", "d"), "x", null, Map.of()).block();
        assertThat(r.success()).isFalse();
        assertThat(r.failureMode()).isEqualTo(SendResult.FailureMode.UNAVAILABLE);
    }

    @Test
    void sendToUnreachableHostReturnsTransientFailure() {
        var ch = new WebhookChannel("hook", "http://127.0.0.1:1/x");
        ch.connect().block();
        SendResult r =
                ch.send(DeliveryTarget.chat("hook", "d"), "msg", null, Map.of())
                        .block(java.time.Duration.ofSeconds(15));
        assertThat(r.success()).isFalse();
        assertThat(r.failureMode()).isEqualTo(SendResult.FailureMode.TRANSIENT);
        ch.disconnect().block();
    }
}
