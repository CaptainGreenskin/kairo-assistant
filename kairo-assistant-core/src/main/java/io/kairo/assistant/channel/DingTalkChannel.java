/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.gateway.DeliveryTarget;

/**
 * DingTalk custom-bot webhook channel — text-only outbound. Inbound from the controller via
 * {@code WebhookChannel.injectInbound}.
 *
 * <p>Outbound shape matches DingTalk's bot API: {@code {"msgtype":"text","text":{"content":...}}}.
 *
 * <p>Note: the production-grade DingTalk integration lives in {@code kairo-channel-dingtalk}
 * (signed-webhook, dedupe, mapper). This class is the lightweight assistant-internal variant used
 * by the {@code /api/channels} REST surface.
 */
public class DingTalkChannel extends WebhookChannel {

    public DingTalkChannel(String channelId, String webhookUrl) {
        super(channelId, webhookUrl);
    }

    @Override
    protected ObjectNode buildPayload(DeliveryTarget target, String content) {
        ObjectNode body = mapper().createObjectNode();
        body.put("msgtype", "text");
        ObjectNode text = body.putObject("text");
        text.put("content", content == null ? "" : content);
        return body;
    }
}
