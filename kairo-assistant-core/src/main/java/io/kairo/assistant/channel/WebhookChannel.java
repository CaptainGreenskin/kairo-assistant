package io.kairo.assistant.channel;

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

/**
 * Generic webhook channel. Outbound POSTs JSON to a configured URL.
 * Inbound via {@link #injectInbound} (call from your HTTP server handler).
 */
public class WebhookChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    private final String channelId;
    private final String webhookUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ChannelInboundHandler> handlerRef = new AtomicReference<>();

    public WebhookChannel(String channelId, String webhookUrl) {
        this.channelId = channelId;
        this.webhookUrl = webhookUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String id() {
        return channelId;
    }

    @Override
    public Mono<Void> start(ChannelInboundHandler handler) {
        return Mono.fromRunnable(() -> {
            if (!running.compareAndSet(false, true)) {
                throw new IllegalStateException("Webhook channel already started");
            }
            handlerRef.set(handler);
            log.info("Webhook channel [{}] started → {}", channelId, webhookUrl);
        });
    }

    @Override
    public Mono<Void> stop() {
        return Mono.fromRunnable(() -> {
            running.set(false);
            handlerRef.set(null);
            log.info("Webhook channel [{}] stopped", channelId);
        });
    }

    @Override
    public ChannelOutboundSender sender() {
        return message -> {
            if (!running.get()) {
                return Mono.just(ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "Not running"));
            }
            return Mono.fromCallable(() -> postWebhook(message));
        };
    }

    public Mono<ChannelAck> injectInbound(String senderId, String text) {
        return injectInbound(senderId, text, Map.of());
    }

    public Mono<ChannelAck> injectInbound(String senderId, String text, Map<String, String> attributes) {
        ChannelInboundHandler handler = handlerRef.get();
        if (handler == null) {
            return Mono.just(ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "No handler"));
        }
        ChannelIdentity identity = new ChannelIdentity(channelId, senderId, attributes);
        return handler.onInbound(ChannelMessage.of(identity, text));
    }

    private ChannelAck postWebhook(ChannelMessage message) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("channel_id", channelId);
            body.put("destination", message.identity().destination());
            body.put("content", message.content());
            body.put("timestamp", message.timestamp().toString());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return ChannelAck.ok();
            }
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, e.getMessage());
        }
    }
}
