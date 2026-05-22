/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.gateway.DeliveryTarget;
import org.junit.jupiter.api.Test;

class DingTalkChannelTest {

    @Test
    void payloadShapeIsDingTalkText() {
        var ch = new DingTalkChannel("dingtalk", "http://127.0.0.1:1/x");
        var body = ch.buildPayload(DeliveryTarget.chat("dingtalk", "c"), "hello");
        assertThat(body.path("msgtype").asText()).isEqualTo("text");
        assertThat(body.path("text").path("content").asText()).isEqualTo("hello");
    }

    @Test
    void inheritsChannelLifecycle() {
        var ch = new DingTalkChannel("dingtalk", "http://127.0.0.1:1/x");
        assertThat(ch.id()).isEqualTo("dingtalk");
        ch.connect().block();
        assertThat(ch.isRunning()).isTrue();
        ch.disconnect().block();
    }
}
