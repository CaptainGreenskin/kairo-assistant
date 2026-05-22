/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.gateway.DeliveryTarget;
import org.junit.jupiter.api.Test;

class TelegramChannelTest {

    @Test
    void payloadShapeIsTelegramSendMessage() {
        var ch = new TelegramChannel("telegram", "http://127.0.0.1:1/x");
        var body = ch.buildPayload(DeliveryTarget.chat("telegram", "12345"), "*hi*");
        assertThat(body.path("chat_id").asText()).isEqualTo("12345");
        assertThat(body.path("text").asText()).isEqualTo("*hi*");
        assertThat(body.path("parse_mode").asText()).isEqualTo("Markdown");
    }
}
