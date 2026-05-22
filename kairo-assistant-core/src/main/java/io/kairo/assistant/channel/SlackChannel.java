/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.gateway.DeliveryTarget;

/**
 * Slack channel using Web API shape: {@code {"channel":"C123","text":"..."}}. POSTed to a configured
 * Slack URL (incoming webhook or chat.postMessage proxy). Inbound via
 * {@link WebhookChannel#injectInbound}.
 */
public class SlackChannel extends WebhookChannel {

    public SlackChannel(String channelId, String webhookUrl) {
        super(channelId, webhookUrl);
    }

    @Override
    protected ObjectNode buildPayload(DeliveryTarget target, String content) {
        ObjectNode body = mapper().createObjectNode();
        body.put("channel", target.chatId() == null ? "" : target.chatId());
        body.put("text", content == null ? "" : content);
        return body;
    }
}
