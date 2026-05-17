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

/**
 * DingTalk channel implementation using the DingTalk Robot Outgoing webhook model.
 *
 * <p>Inbound: receives POST from DingTalk webhook callback (not implemented here — needs an HTTP
 * server or Spring controller to forward messages). Use {@link #injectInbound(String, String,
 * String)} to programmatically push messages.
 *
 * <p>Outbound: sends messages via DingTalk Robot webhook URL.
 */
public class DingTalkChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannel.class);

    private final String channelId;
    private final String webhookUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ChannelInboundHandler> handlerRef = new AtomicReference<>();

    public DingTalkChannel(String channelId, String webhookUrl) {
        this.channelId = channelId;
        this.webhookUrl = webhookUrl;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    DingTalkChannel(String channelId, String webhookUrl, HttpClient httpClient) {
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
                throw new IllegalStateException("DingTalk channel already started");
            }
            handlerRef.set(handler);
            log.info("DingTalk channel [{}] started", channelId);
        });
    }

    @Override
    public Mono<Void> stop() {
        return Mono.fromRunnable(() -> {
            running.set(false);
            handlerRef.set(null);
            log.info("DingTalk channel [{}] stopped", channelId);
        });
    }

    @Override
    public ChannelOutboundSender sender() {
        return message -> {
            if (!running.get()) {
                return Mono.just(
                        ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "Channel not running"));
            }
            return Mono.fromCallable(() -> sendToDingTalk(message));
        };
    }

    /**
     * Programmatically inject an inbound message (for webhook callback integration).
     */
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

    private ChannelAck sendToDingTalk(ChannelMessage message) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("msgtype", "text");
            ObjectNode text = body.putObject("text");
            text.put("content", message.content());

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(webhookUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body)))
                            .timeout(Duration.ofSeconds(10))
                            .build();

            HttpResponse<String> resp =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode respBody = mapper.readTree(resp.body());
                int errcode = respBody.path("errcode").asInt(-1);
                if (errcode == 0) {
                    return ChannelAck.ok();
                }
                return ChannelAck.fail(
                        ChannelFailureMode.REJECTED,
                        "DingTalk errcode=" + errcode + ": " + respBody.path("errmsg").asText());
            }
            return ChannelAck.fail(
                    ChannelFailureMode.SEND_FAILED,
                    "HTTP " + resp.statusCode());
        } catch (Exception e) {
            log.error("Failed to send to DingTalk", e);
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, e.getMessage());
        }
    }
}
