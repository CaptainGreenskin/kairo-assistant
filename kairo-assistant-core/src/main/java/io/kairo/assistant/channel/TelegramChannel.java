/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.gateway.DeliveryTarget;

/**
 * Telegram bot channel. Outbound shape: {@code {"chat_id":...,"text":"...","parse_mode":"Markdown"}}.
 * Sends to the bot's {@code sendMessage} endpoint (URL passed in by configuration).
 */
public class TelegramChannel extends WebhookChannel {

    public TelegramChannel(String channelId, String botSendUrl) {
        super(channelId, botSendUrl);
    }

    @Override
    protected ObjectNode buildPayload(DeliveryTarget target, String content) {
        ObjectNode body = mapper().createObjectNode();
        body.put("chat_id", target.chatId() == null ? "" : target.chatId());
        body.put("text", content == null ? "" : content);
        body.put("parse_mode", "Markdown");
        return body;
    }
}
