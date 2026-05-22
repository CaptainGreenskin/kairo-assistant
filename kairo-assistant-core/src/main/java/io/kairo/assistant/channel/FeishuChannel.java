/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.gateway.DeliveryTarget;

/**
 * Feishu / Lark custom-robot webhook channel. Outbound shape:
 * {@code {"msg_type":"text","content":{"text":"..."}}}. POSTs to a configured webhook URL.
 */
public class FeishuChannel extends WebhookChannel {

    public FeishuChannel(String channelId, String webhookUrl) {
        super(channelId, webhookUrl);
    }

    @Override
    protected ObjectNode buildPayload(DeliveryTarget target, String content) {
        ObjectNode body = mapper().createObjectNode();
        body.put("msg_type", "text");
        ObjectNode contentNode = body.putObject("content");
        contentNode.put("text", content == null ? "" : content);
        return body;
    }
}
