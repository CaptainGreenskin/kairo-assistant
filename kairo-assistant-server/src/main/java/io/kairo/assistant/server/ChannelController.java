/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.server;

import io.kairo.api.gateway.DeliveryTarget;
import io.kairo.api.gateway.SendResult;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.channel.ChannelGateway;
import io.kairo.assistant.channel.DingTalkChannel;
import io.kairo.assistant.channel.FeishuChannel;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST surface for the Console "Channels" tab. Backed by the new {@code io.kairo.api.gateway}
 * SPI — each channel is a {@link io.kairo.api.gateway.Channel} implementation with a
 * {@link ChannelGateway} wrapper bridging inbound → agent → outbound.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/channels} — list configured + available channels
 *   <li>{@code POST /api/channels/dingtalk/webhook} — receive DingTalk inbound
 *   <li>{@code POST /api/channels/feishu/webhook} — receive Feishu inbound
 *   <li>{@code POST /api/channels/{id}/send} — send arbitrary text outbound via a configured channel
 *   <li>{@code GET /api/channels/{id}/recent} — last 50 inbound + outbound messages (in-memory)
 * </ul>
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    private static final int RECENT_LIMIT = 50;

    private final AssistantSession session;
    private final Map<String, ChannelGateway> gateways = new ConcurrentHashMap<>();
    private final Map<String, Object> channels = new ConcurrentHashMap<>();
    private final Map<String, Deque<RecentMessage>> recent = new ConcurrentHashMap<>();

    public ChannelController(AssistantSession session) {
        this.session = session;
    }

    private record RecentMessage(
            String direction,
            String destination,
            String content,
            long timestampMillis,
            boolean success) {}

    private synchronized void recordRecent(
            String channelId,
            String direction,
            String destination,
            String content,
            boolean success) {
        Deque<RecentMessage> dq =
                recent.computeIfAbsent(channelId, k -> new ArrayDeque<>(RECENT_LIMIT + 1));
        dq.addFirst(
                new RecentMessage(
                        direction, destination, content, System.currentTimeMillis(), success));
        while (dq.size() > RECENT_LIMIT) dq.removeLast();
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> channelEntries = new ArrayList<>();
        for (var entry : gateways.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", entry.getKey());
            m.put("status", "active");
            channelEntries.add(m);
        }
        String[] supported = {"dingtalk", "feishu", "slack", "telegram", "webhook"};
        for (String s : supported) {
            if (gateways.containsKey(s)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s);
            m.put("status", "available");
            channelEntries.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", channelEntries.size());
        result.put("active", gateways.size());
        result.put("items", channelEntries);
        return result;
    }

    @PostMapping(value = "/dingtalk/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> dingTalkWebhook(@RequestBody Map<String, Object> body) {
        String text = extractText(body);
        if (text == null || text.isBlank()) {
            return Map.of("error", "empty message text");
        }
        String senderId = String.valueOf(body.getOrDefault("senderId", "unknown"));
        DingTalkChannel channel = findOrCreateDingTalk();
        channel.injectInbound(senderId, text);
        recordRecent("dingtalk", "in", senderId, text, true);
        return Map.of("success", true);
    }

    @PostMapping(value = "/feishu/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> feishuWebhook(@RequestBody Map<String, Object> body) {
        String text = extractFeishuText(body);
        if (text == null || text.isBlank()) {
            return Map.of("error", "empty message text");
        }
        String senderId =
                extractNestedString(body, "event", "sender", "sender_id", "open_id");
        if (senderId == null) senderId = "unknown";
        FeishuChannel channel = findOrCreateFeishu();
        channel.injectInbound(senderId, text);
        recordRecent("feishu", "in", senderId, text, true);
        return Map.of("success", true);
    }

    @PostMapping(value = "/{channelId}/send", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> send(
            @PathVariable String channelId, @RequestBody Map<String, String> body) {
        String dest = body.getOrDefault("destination", "default");
        String content = body.getOrDefault("content", "");
        if (content.isBlank()) {
            return Mono.just(Map.of("error", "content must not be empty"));
        }

        ChannelGateway gateway = gateways.get(channelId);
        if (gateway == null) {
            return Mono.just(Map.of("error", "Channel not found: " + channelId));
        }

        DeliveryTarget target = DeliveryTarget.chat(channelId, dest);
        return gateway.sendOutbound(target, content)
                .map(
                        (SendResult result) -> {
                            recordRecent(channelId, "out", dest, content, result.success());
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("sent", result.success());
                            if (!result.success()) r.put("error", result.errorMessage());
                            return r;
                        })
                .onErrorResume(
                        e -> {
                            recordRecent(channelId, "out", dest, content, false);
                            return Mono.just(Map.of("error", e.getMessage()));
                        });
    }

    /**
     * Recent inbound + outbound messages for a channel. Backed by an in-memory ring buffer, so
     * values reset on server restart. Useful for debugging integrations without tailing logs.
     */
    @GetMapping(value = "/{channelId}/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> recentMessages(
            @PathVariable String channelId,
            @RequestParam(defaultValue = "50") int limit) {
        Deque<RecentMessage> dq = recent.get(channelId);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (dq != null) {
            int i = 0;
            for (RecentMessage m : dq) {
                if (i++ >= limit) break;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("direction", m.direction());
                row.put("destination", m.destination());
                row.put("content", m.content());
                row.put("timestamp", Instant.ofEpochMilli(m.timestampMillis()).toString());
                row.put("success", m.success());
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channelId", channelId);
        result.put("total", rows.size());
        result.put("items", rows);
        return result;
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> body) {
        Object textObj = body.get("text");
        if (textObj instanceof Map) {
            return String.valueOf(((Map<String, Object>) textObj).getOrDefault("content", ""));
        }
        return String.valueOf(body.getOrDefault("content", body.getOrDefault("text", "")));
    }

    @SuppressWarnings("unchecked")
    private String extractFeishuText(Map<String, Object> body) {
        Object event = body.get("event");
        if (event instanceof Map) {
            Object message = ((Map<String, Object>) event).get("message");
            if (message instanceof Map) {
                return String.valueOf(((Map<String, Object>) message).getOrDefault("content", ""));
            }
        }
        return String.valueOf(body.getOrDefault("content", ""));
    }

    @SuppressWarnings("unchecked")
    private String extractNestedString(Map<String, Object> body, String... keys) {
        Object current = body;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return null;
            }
        }
        return current instanceof String s ? s : null;
    }

    // Channel init blocks on gw.start(); keep it out of ConcurrentHashMap mapping functions
    // (which hold a bin lock) by serializing on the monitor with a double check instead.
    private synchronized DingTalkChannel findOrCreateDingTalk() {
        DingTalkChannel existing = (DingTalkChannel) channels.get("dingtalk");
        if (existing != null) return existing;
        String webhookUrl = System.getenv("DINGTALK_WEBHOOK_URL");
        if (webhookUrl == null) {
            webhookUrl = "https://oapi.dingtalk.com/robot/send";
        }
        DingTalkChannel channel = new DingTalkChannel("dingtalk", webhookUrl);
        ChannelGateway gw = new ChannelGateway(channel, session.agent());
        gw.start().block();
        gateways.put("dingtalk", gw);
        channels.put("dingtalk", channel);
        return channel;
    }

    private synchronized FeishuChannel findOrCreateFeishu() {
        FeishuChannel existing = (FeishuChannel) channels.get("feishu");
        if (existing != null) return existing;
        String webhookUrl = System.getenv("FEISHU_WEBHOOK_URL");
        if (webhookUrl == null) {
            webhookUrl = "https://open.feishu.cn/open-apis/bot/v2/hook/unconfigured";
        }
        FeishuChannel channel = new FeishuChannel("feishu", webhookUrl);
        ChannelGateway gw = new ChannelGateway(channel, session.agent());
        gw.start().block();
        gateways.put("feishu", gw);
        channels.put("feishu", channel);
        return channel;
    }
}
