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
 * Slack channel using Slack Web API for outbound.
 * Inbound: use {@link #injectInbound} programmatically (e.g., from a Slack Events API webhook).
 */
public class SlackChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);
    private static final String SLACK_API = "https://slack.com/api/";

    private final String channelId;
    private final String botToken;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ChannelInboundHandler> handlerRef = new AtomicReference<>();

    public SlackChannel(String channelId, String botToken) {
        this.channelId = channelId;
        this.botToken = botToken;
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
                throw new IllegalStateException("Slack channel already started");
            }
            handlerRef.set(handler);
            log.info("Slack channel [{}] started", channelId);
        });
    }

    @Override
    public Mono<Void> stop() {
        return Mono.fromRunnable(() -> {
            running.set(false);
            handlerRef.set(null);
            log.info("Slack channel [{}] stopped", channelId);
        });
    }

    @Override
    public ChannelOutboundSender sender() {
        return message -> {
            if (!running.get()) {
                return Mono.just(ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "Not running"));
            }
            return Mono.fromCallable(() -> sendToSlack(message));
        };
    }

    public Mono<ChannelAck> injectInbound(String slackChannel, String userId, String userName, String text) {
        ChannelInboundHandler handler = handlerRef.get();
        if (handler == null) {
            return Mono.just(ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "No handler"));
        }
        ChannelIdentity identity = new ChannelIdentity(channelId, slackChannel,
                Map.of("userId", userId, "userName", userName));
        return handler.onInbound(ChannelMessage.of(identity, text));
    }

    private ChannelAck sendToSlack(ChannelMessage message) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("channel", message.identity().destination());
            body.put("text", message.content());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SLACK_API + "chat.postMessage"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + botToken)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode result = mapper.readTree(resp.body());

            if (result.path("ok").asBoolean()) {
                return ChannelAck.ok(result.path("ts").asText());
            }
            return ChannelAck.fail(ChannelFailureMode.REJECTED,
                    result.path("error").asText("Unknown error"));
        } catch (Exception e) {
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, e.getMessage());
        }
    }
}
