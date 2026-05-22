/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.gateway.DeliveryTarget;
import org.junit.jupiter.api.Test;

class SlackChannelTest {

    @Test
    void payloadShapeIsSlackChatPostMessage() {
        var ch = new SlackChannel("slack", "http://127.0.0.1:1/x");
        var body = ch.buildPayload(DeliveryTarget.chat("slack", "C-123"), "hi");
        assertThat(body.path("channel").asText()).isEqualTo("C-123");
        assertThat(body.path("text").asText()).isEqualTo("hi");
    }
}
