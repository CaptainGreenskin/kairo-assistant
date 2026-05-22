/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.gateway.DeliveryTarget;
import org.junit.jupiter.api.Test;

class FeishuChannelTest {

    @Test
    void payloadShapeIsFeishuText() {
        var ch = new FeishuChannel("feishu", "http://127.0.0.1:1/x");
        var body = ch.buildPayload(DeliveryTarget.chat("feishu", "c"), "hi");
        assertThat(body.path("msg_type").asText()).isEqualTo("text");
        assertThat(body.path("content").path("text").asText()).isEqualTo("hi");
    }

    @Test
    void id() {
        assertThat(new FeishuChannel("feishu", "http://127.0.0.1:1/x").id()).isEqualTo("feishu");
    }
}
