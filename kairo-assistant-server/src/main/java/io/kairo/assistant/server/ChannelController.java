package io.kairo.assistant.server;

import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelIdentity;
import io.kairo.api.channel.ChannelMessage;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.channel.ChannelGateway;
import io.kairo.assistant.channel.DingTalkChannel;
import io.kairo.assistant.channel.FeishuChannel;
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
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    private final AssistantSession session;
    private final Map<String, ChannelGateway> gateways = new ConcurrentHashMap<>();

    public ChannelController(AssistantSession session) {
        this.session = session;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> channels = gateways.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getKey());
                    m.put("status", "active");
                    return m;
                }).toList();

        String[] supported = {"dingtalk", "feishu", "slack", "telegram", "webhook"};
        for (String s : supported) {
            if (gateways.containsKey(s)) continue;
            channels = new java.util.ArrayList<>(channels);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s);
            m.put("status", "available");
            channels.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", channels.size());
        result.put("active", gateways.size());
        result.put("channels", channels);
        return result;
    }

    @PostMapping(value = "/dingtalk/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> dingTalkWebhook(@RequestBody Map<String, Object> body) {
        String text = extractText(body);
        if (text == null || text.isBlank()) {
            return Mono.just(Map.of("error", (Object) "empty message text"));
        }
        String senderId = String.valueOf(body.getOrDefault("senderId", "unknown"));
        String senderName = String.valueOf(body.getOrDefault("senderNick", "user"));

        DingTalkChannel channel = findOrCreateDingTalk();
        return channel.injectInbound(senderId, senderName, text)
                .map(ack -> Map.<String, Object>of("success", ack.success()));
    }

    @PostMapping(value = "/feishu/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> feishuWebhook(@RequestBody Map<String, Object> body) {
        String text = extractFeishuText(body);
        if (text == null || text.isBlank()) {
            return Mono.just(Map.of("error", (Object) "empty message text"));
        }
        String senderId = extractNestedString(body, "event", "sender", "sender_id", "open_id");
        if (senderId == null) senderId = "unknown";

        FeishuChannel channel = findOrCreateFeishu();
        return channel.injectInbound(senderId, "user", text)
                .map(ack -> Map.<String, Object>of("success", ack.success()));
    }

    @PostMapping(value = "/{channelId}/send", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> send(
            @PathVariable String channelId,
            @RequestBody Map<String, String> body) {
        String dest = body.getOrDefault("destination", "default");
        String content = body.getOrDefault("content", "");

        if (content.isBlank()) {
            return Mono.just(Map.of("error", (Object) "content must not be empty"));
        }

        ChannelIdentity identity = ChannelIdentity.of(channelId, dest);
        ChannelMessage message = ChannelMessage.of(identity, content);

        ChannelGateway gateway = gateways.get(channelId);
        if (gateway == null) {
            return Mono.just(Map.of("error", (Object) "Channel not found: " + channelId));
        }

        return gateway.sendOutbound(message)
                .map(ack -> Map.<String, Object>of("sent", ack.success()))
                .onErrorResume(e -> Mono.just(Map.of("error", (Object) e.getMessage())));
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

    private final Map<String, Object> channels = new ConcurrentHashMap<>();

    private DingTalkChannel findOrCreateDingTalk() {
        return (DingTalkChannel) channels.computeIfAbsent("dingtalk", id -> {
            String webhookUrl = System.getenv("DINGTALK_WEBHOOK_URL");
            if (webhookUrl == null) webhookUrl = "https://oapi.dingtalk.com/robot/send";
            DingTalkChannel channel = new DingTalkChannel("dingtalk", webhookUrl);
            ChannelGateway gw = new ChannelGateway(channel, session.agent());
            gw.start().subscribe();
            gateways.put("dingtalk", gw);
            return channel;
        });
    }

    private FeishuChannel findOrCreateFeishu() {
        return (FeishuChannel) channels.computeIfAbsent("feishu", id -> {
            String webhookUrl = System.getenv("FEISHU_WEBHOOK_URL");
            if (webhookUrl == null) webhookUrl = "https://open.feishu.cn/open-apis/bot/v2/hook/unconfigured";
            FeishuChannel channel = new FeishuChannel("feishu", webhookUrl);
            ChannelGateway gw = new ChannelGateway(channel, session.agent());
            gw.start().subscribe();
            gateways.put("feishu", gw);
            return channel;
        });
    }
}
