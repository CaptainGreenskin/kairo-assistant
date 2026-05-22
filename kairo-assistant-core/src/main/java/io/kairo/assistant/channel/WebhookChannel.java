/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.gateway.Channel;
import io.kairo.api.gateway.ChannelMessage;
import io.kairo.api.gateway.DeliveryTarget;
import io.kairo.api.gateway.MessageType;
import io.kairo.api.gateway.PlatformCapabilities;
import io.kairo.api.gateway.SendResult;
import io.kairo.api.gateway.SessionSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Generic webhook channel. Outbound POSTs JSON to a configured URL; inbound is driven by callers
 * via {@link #injectInbound} (typically the HTTP webhook controller).
 *
 * <p>Migrated to the unified {@link io.kairo.api.gateway.Channel} SPI as part of the
 * channel-into-gateway collapse.
 */
public class WebhookChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    private final String channelId;
    private final String webhookUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Sinks.Many<ChannelMessage> inbound =
            Sinks.many().multicast().onBackpressureBuffer();

    public WebhookChannel(String channelId, String webhookUrl) {
        this.channelId = channelId;
        this.webhookUrl = webhookUrl;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String id() {
        return channelId;
    }

    @Override
    public PlatformCapabilities capabilities() {
        return PlatformCapabilities.textOnly();
    }

    @Override
    public Mono<Void> connect() {
        return Mono.fromRunnable(
                () -> {
                    if (!running.compareAndSet(false, true)) {
                        throw new IllegalStateException(
                                "Webhook channel '" + channelId + "' already connected");
                    }
                    log.info("Webhook channel [{}] connected → {}", channelId, webhookUrl);
                });
    }

    @Override
    public Mono<Void> disconnect() {
        return Mono.fromRunnable(
                () -> {
                    running.set(false);
                    inbound.tryEmitComplete();
                    log.info("Webhook channel [{}] disconnected", channelId);
                });
    }

    @Override
    public Flux<ChannelMessage> inbound() {
        return inbound.asFlux();
    }

    @Override
    public Mono<SendResult> send(
            DeliveryTarget target,
            String content,
            String replyToMessageId,
            Map<String, Object> metadata) {
        if (!running.get()) {
            return Mono.just(
                    SendResult.fail(SendResult.FailureMode.UNAVAILABLE, "channel not connected"));
        }
        return Mono.fromCallable(() -> postWebhook(target, content));
    }

    /** Inject a synthetic inbound text message; used by the webhook HTTP controller. */
    public void injectInbound(String senderId, String text) {
        injectInbound(senderId, text, Map.of());
    }

    public void injectInbound(String senderId, String text, Map<String, Object> attributes) {
        if (!running.get()) {
            log.debug("Webhook channel [{}] dropped inbound — not connected", channelId);
            return;
        }
        ChannelMessage msg =
                new ChannelMessage(
                        UUID.randomUUID().toString(),
                        SessionSource.of(channelId, senderId, senderId),
                        MessageType.TEXT,
                        text,
                        List.of(),
                        null,
                        null,
                        null,
                        Instant.now(),
                        attributes == null ? Map.of() : Map.copyOf(attributes));
        var emit = inbound.tryEmitNext(msg);
        if (emit.isFailure()) {
            log.warn("Webhook channel [{}] dropped inbound from {}: {}", channelId, senderId, emit);
        }
    }

    /**
     * Build the outbound JSON payload. Subclasses override to render platform-specific shapes
     * (DingTalk msgtype, Slack channel+text, Telegram chat_id, Feishu msg_type, etc.).
     */
    protected ObjectNode buildPayload(DeliveryTarget target, String content) {
        ObjectNode body = mapper.createObjectNode();
        body.put("channel_id", channelId);
        body.put("destination", target.chatId() == null ? "" : target.chatId());
        body.put("content", content == null ? "" : content);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    /** Subclasses override to point sends at a per-target webhook (e.g. Telegram per-bot URL). */
    protected String resolveWebhookUrl(DeliveryTarget target) {
        return webhookUrl;
    }

    /** Visible for subclasses constructing custom payloads. */
    protected ObjectMapper mapper() {
        return mapper;
    }

    private SendResult postWebhook(DeliveryTarget target, String content) {
        try {
            ObjectNode body = buildPayload(target, content);
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(resolveWebhookUrl(target)))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(body)))
                            .timeout(Duration.ofSeconds(10))
                            .build();
            HttpResponse<String> resp =
                    httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return SendResult.ok(null);
            }
            return SendResult.fail(
                    SendResult.FailureMode.TRANSIENT, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            return SendResult.fail(SendResult.FailureMode.TRANSIENT, e.getMessage());
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
