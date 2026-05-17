package io.kairo.assistant.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.channel.Channel;
import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelFailureMode;
import io.kairo.api.channel.ChannelIdentity;
import io.kairo.api.channel.ChannelInboundHandler;
import io.kairo.api.channel.ChannelMessage;
import io.kairo.api.channel.ChannelOutboundSender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class FeishuChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);

    private final String channelId;
    private final String webhookUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ChannelInboundHandler> handlerRef = new AtomicReference<>();

    public FeishuChannel(String channelId, String webhookUrl) {
        this.channelId = channelId;
        this.webhookUrl = webhookUrl;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    FeishuChannel(String channelId, String webhookUrl, HttpClient httpClient) {
        this.channelId = channelId;
        this.webhookUrl = webhookUrl;
        this.httpClient = httpClient;
    }

    @Override
    public String id() {
        return channelId;
    }

    @Override
    public Mono<Void> start(ChannelInboundHandler handler) {
        return Mono.fromRunnable(() -> {
            if (!running.compareAndSet(false, true)) {
                throw new IllegalStateException("Feishu channel already started");
            }
            handlerRef.set(handler);
            log.info("Feishu channel [{}] started", channelId);
        });
    }

    @Override
    public Mono<Void> stop() {
        return Mono.fromRunnable(() -> {
            running.set(false);
            handlerRef.set(null);
            log.info("Feishu channel [{}] stopped", channelId);
        });
    }

    @Override
    public ChannelOutboundSender sender() {
        return message -> {
            if (!running.get()) {
                return Mono.just(
                        ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "Channel not running"));
            }
            return Mono.fromCallable(() -> sendToFeishu(message));
        };
    }

    public Mono<ChannelAck> injectInbound(String senderId, String senderName, String text) {
        ChannelInboundHandler handler = handlerRef.get();
        if (handler == null) {
            return Mono.just(
                    ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "No handler registered"));
        }

        ChannelIdentity identity =
                new ChannelIdentity(
                        channelId,
                        senderId,
                        Map.of("senderName", senderName));
        ChannelMessage message = ChannelMessage.of(identity, text);
        return handler.onInbound(message);
    }

    public Mono<ChannelAck> injectInboundCard(String senderId, String senderName,
                                                String title, String content) {
        ChannelInboundHandler handler = handlerRef.get();
        if (handler == null) {
            return Mono.just(
                    ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "No handler registered"));
        }

        ChannelIdentity identity =
                new ChannelIdentity(
                        channelId,
                        senderId,
                        Map.of("senderName", senderName, "msgType", "interactive"));
        ChannelMessage message = ChannelMessage.of(identity, content);
        return handler.onInbound(message);
    }

    private ChannelAck sendToFeishu(ChannelMessage message) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("msg_type", "text");
            ObjectNode content = body.putObject("content");
            content.put("text", message.content());

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(webhookUrl))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body)))
                            .timeout(Duration.ofSeconds(10))
                            .build();

            HttpResponse<String> resp =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode respBody = mapper.readTree(resp.body());
                int code = respBody.path("code").asInt(-1);
                if (code == 0) {
                    return ChannelAck.ok();
                }
                return ChannelAck.fail(
                        ChannelFailureMode.REJECTED,
                        "Feishu code=" + code + ": " + respBody.path("msg").asText());
            }
            return ChannelAck.fail(
                    ChannelFailureMode.SEND_FAILED,
                    "HTTP " + resp.statusCode());
        } catch (Exception e) {
            log.error("Failed to send to Feishu", e);
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, e.getMessage());
        }
    }

    public ChannelAck sendRichText(String title, String content) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("msg_type", "post");
            ObjectNode postContent = body.putObject("content");
            ObjectNode post = postContent.putObject("post");
            ObjectNode zhCn = post.putObject("zh_cn");
            zhCn.put("title", title);
            zhCn.putArray("content")
                    .addArray()
                    .addObject()
                    .put("tag", "text")
                    .put("text", content);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(webhookUrl))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body)))
                            .timeout(Duration.ofSeconds(10))
                            .build();

            HttpResponse<String> resp =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode respBody = mapper.readTree(resp.body());
                int code = respBody.path("code").asInt(-1);
                if (code == 0) {
                    return ChannelAck.ok();
                }
                return ChannelAck.fail(
                        ChannelFailureMode.REJECTED,
                        "Feishu code=" + code + ": " + respBody.path("msg").asText());
            }
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            log.error("Failed to send rich text to Feishu", e);
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, e.getMessage());
        }
    }

    public ChannelAck sendInteractiveCard(String cardJson) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("msg_type", "interactive");
            body.set("card", mapper.readTree(cardJson));

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(webhookUrl))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body)))
                            .timeout(Duration.ofSeconds(10))
                            .build();

            HttpResponse<String> resp =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode respBody = mapper.readTree(resp.body());
                int code = respBody.path("code").asInt(-1);
                if (code == 0) {
                    return ChannelAck.ok();
                }
                return ChannelAck.fail(
                        ChannelFailureMode.REJECTED,
                        "Feishu code=" + code + ": " + respBody.path("msg").asText());
            }
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            log.error("Failed to send card to Feishu", e);
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, e.getMessage());
        }
    }
}
