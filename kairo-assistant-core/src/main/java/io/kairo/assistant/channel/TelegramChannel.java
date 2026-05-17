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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Telegram Bot channel using long-polling (getUpdates API).
 * Outbound via sendMessage API.
 */
public class TelegramChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    private static final String BASE_URL = "https://api.telegram.org/bot";

    private final String channelId;
    private final String botToken;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ChannelInboundHandler> handlerRef = new AtomicReference<>();
    private final AtomicLong lastUpdateId = new AtomicLong(0);
    private ScheduledExecutorService poller;

    public TelegramChannel(String channelId, String botToken) {
        this.channelId = channelId;
        this.botToken = botToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    TelegramChannel(String channelId, String botToken, HttpClient httpClient) {
        this.channelId = channelId;
        this.botToken = botToken;
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
                throw new IllegalStateException("Telegram channel already started");
            }
            handlerRef.set(handler);
            poller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "telegram-poller");
                t.setDaemon(true);
                return t;
            });
            poller.scheduleWithFixedDelay(this::pollUpdates, 0, 1, TimeUnit.SECONDS);
            log.info("Telegram channel [{}] started polling", channelId);
        });
    }

    @Override
    public Mono<Void> stop() {
        return Mono.fromRunnable(() -> {
            running.set(false);
            handlerRef.set(null);
            if (poller != null) {
                poller.shutdownNow();
            }
            log.info("Telegram channel [{}] stopped", channelId);
        });
    }

    @Override
    public ChannelOutboundSender sender() {
        return message -> {
            if (!running.get()) {
                return Mono.just(ChannelAck.fail(ChannelFailureMode.SEND_FAILED, "Not running"));
            }
            return Mono.fromCallable(() -> sendMessage(message));
        };
    }

    private void pollUpdates() {
        if (!running.get()) return;
        try {
            String url = BASE_URL + botToken + "/getUpdates?timeout=25&offset=" + (lastUpdateId.get() + 1);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            if (!root.path("ok").asBoolean()) return;

            JsonNode results = root.get("result");
            if (results == null || !results.isArray()) return;

            for (JsonNode update : results) {
                long updateId = update.path("update_id").asLong();
                lastUpdateId.set(updateId);

                JsonNode msg = update.get("message");
                if (msg == null) continue;
                String text = msg.path("text").asText(null);
                if (text == null) continue;

                String chatId = String.valueOf(msg.path("chat").path("id").asLong());
                String from = msg.path("from").path("first_name").asText("unknown");

                ChannelInboundHandler handler = handlerRef.get();
                if (handler != null) {
                    ChannelIdentity identity = new ChannelIdentity(channelId, chatId,
                            Map.of("senderName", from));
                    handler.onInbound(ChannelMessage.of(identity, text)).subscribe();
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.warn("Telegram poll error: {}", e.getMessage());
            }
        }
    }

    private ChannelAck sendMessage(ChannelMessage message) {
        try {
            String chatId = message.identity().destination();
            ObjectNode body = mapper.createObjectNode();
            body.put("chat_id", chatId);
            body.put("text", message.content());
            body.put("parse_mode", "Markdown");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode result = mapper.readTree(resp.body());
            if (result.path("ok").asBoolean()) {
                String msgId = String.valueOf(result.path("result").path("message_id").asLong());
                return ChannelAck.ok(msgId);
            }
            return ChannelAck.fail(ChannelFailureMode.REJECTED,
                    result.path("description").asText("Unknown error"));
        } catch (Exception e) {
            return ChannelAck.fail(ChannelFailureMode.SEND_FAILED, e.getMessage());
        }
    }
}
