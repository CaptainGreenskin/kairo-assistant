package io.kairo.assistant.server;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.gateway.ModelSwitchService;
import io.kairo.core.session.SessionKey;
import io.kairo.core.session.UnifiedGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DingTalkStreamRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamRunner.class);
    private static final String ROBOT_MSG_TOPIC = "/v1.0/im/bot/messages/get";

    private final UnifiedGateway gateway;
    private final ModelSwitchService modelSwitchService;
    private final OutboundMessageRouter outboundRouter;
    private final SessionMirror sessionMirror;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ConcurrentHashMap<String, Runnable> inProgressTasks = new ConcurrentHashMap<>();

    public DingTalkStreamRunner(UnifiedGateway gateway, ModelSwitchService modelSwitchService,
                                OutboundMessageRouter outboundRouter,
                                SessionMirror sessionMirror) {
        this.gateway = gateway;
        this.modelSwitchService = modelSwitchService;
        this.outboundRouter = outboundRouter;
        this.sessionMirror = sessionMirror;
    }

    @Override
    public void run(String... args) {
        String clientId = System.getenv("DINGTALK_CLIENT_ID");
        String clientSecret = System.getenv("DINGTALK_CLIENT_SECRET");

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            log.info("DingTalk Stream Mode disabled (DINGTALK_CLIENT_ID/SECRET not set)");
            return;
        }

        outboundRouter.register("dingtalk", (destination, message) -> {
            try {
                ObjectNode body = mapper.createObjectNode();
                body.put("msgtype", "markdown");
                ObjectNode md = body.putObject("markdown");
                md.put("title", "Notification");
                md.put("text", message);
                doPost(destination, body);
                return true;
            } catch (Exception e) {
                log.error("DingTalk outbound send failed to [{}]: {}", destination, e.getMessage());
                return false;
            }
        });

        try {
            OpenDingTalkClient client = OpenDingTalkStreamClientBuilder.custom()
                    .credential(new AuthClientCredential(clientId, clientSecret))
                    .registerCallbackListener(ROBOT_MSG_TOPIC, new RobotMessageListener())
                    .build();
            client.start();
            log.info("DingTalk Stream Mode connected (clientId={}...)",
                    clientId.substring(0, Math.min(8, clientId.length())));
        } catch (Exception e) {
            log.error("Failed to start DingTalk Stream Mode: {}", e.getMessage(), e);
        }
    }

    private class RobotMessageListener implements OpenDingTalkCallbackListener<String, String> {

        @Override
        public String execute(String payload) {
            try {
                JsonNode request = mapper.readTree(payload);
                String text = extractText(request);
                String msgType = extractMsgType(request);
                String imageUrl = null;
                String sessionWebhook = request.path("sessionWebhook").asText(null);
                String senderNick = request.path("senderNick").asText("user");
                String senderId = request.path("senderId").asText("unknown");
                String conversationId = request.path("conversationId").asText("unknown");
                String conversationType = request.path("conversationType").asText("1");
                boolean isGroup = "2".equals(conversationType);

                if ("picture".equals(msgType)) {
                    imageUrl = extractImageUrl(request);
                    if (imageUrl == null) {
                        log.debug("Ignoring picture message with no download URL");
                        return "{}";
                    }
                } else if ((text == null || text.isBlank())) {
                    log.debug("Ignoring empty DingTalk message");
                    return "{}";
                }

                if (isGroup && !isBotMentioned(request)) {
                    log.debug("Ignoring group message without @mention from [{}]", senderNick);
                    return "{}";
                }

                if (isGroup && text != null) {
                    text = stripAtBotPrefix(text);
                }

                String displayText = imageUrl != null ? "[image]" :
                        (text.length() > 100 ? text.substring(0, 100) + "..." : text);
                log.info("DingTalk inbound from [{}] in [{}]({}): {}", senderNick, conversationId,
                        isGroup ? "group" : "dm", displayText);

                String sessionDest = isGroup
                        ? conversationId + ":" + senderId
                        : conversationId;

                if (imageUrl != null) {
                    processAsyncMultimodal(text, imageUrl, sessionWebhook, senderNick, sessionDest);
                } else {
                    processAsync(text, sessionWebhook, senderNick, sessionDest);
                }

            } catch (Exception e) {
                log.error("Error handling DingTalk stream message", e);
            }
            return "{}";
        }
    }

    private boolean isBotMentioned(JsonNode request) {
        JsonNode atUsers = request.path("atUsers");
        if (atUsers.isArray() && !atUsers.isEmpty()) {
            return true;
        }
        return request.path("isInAtList").asBoolean(false);
    }

    private String stripAtBotPrefix(String text) {
        return text.replaceFirst("^@\\S+\\s*", "").trim();
    }

    private void processAsync(String text, String sessionWebhook, String senderNick,
                              String sessionDest) {
        cancelInProgress(sessionDest);

        SessionKey key = SessionKey.of("dingtalk", sessionDest);

        if (text.startsWith("/model")) {
            String modelId = text.substring("/model".length()).trim();
            if (modelId.isEmpty()) {
                String available = String.join(", ", modelSwitchService.registry().aliases());
                sendTextReply(sessionWebhook, "Available models: " + available);
            } else {
                ModelSwitchService.SwitchResult result = modelSwitchService.switchModel(key, modelId);
                sendTextReply(sessionWebhook, result.message());
            }
            return;
        }

        sessionMirror.onInbound(key, "dingtalk", text);

        if (sessionWebhook != null) {
            sendTextReply(sessionWebhook, "正在思考...");
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);
        inProgressTasks.put(sessionDest, () -> {
            cancelled.set(true);
            gateway.interrupt(key);
        });

        Thread t = new Thread(() -> {
            try {
                var response = gateway.route(key, Msg.of(MsgRole.USER, text))
                        .block(Duration.ofMinutes(5));

                if (!cancelled.get() && response != null && sessionWebhook != null) {
                    String reply = response.text();
                    reply = reply.replaceAll("<think>[\\s\\S]*?</think>\\s*", "");
                    if (reply.isBlank()) {
                        reply = "(No response)";
                    }
                    sendMarkdownReply(sessionWebhook, reply);
                    sessionMirror.onOutbound(key, "dingtalk", reply);
                    log.info("DingTalk reply sent to [{}]: {}",
                            senderNick, reply.length() > 80 ? reply.substring(0, 80) + "..." : reply);
                }
            } catch (Exception e) {
                if (!cancelled.get()) {
                    log.error("Error processing DingTalk message", e);
                    if (sessionWebhook != null) {
                        sendTextReply(sessionWebhook, "Error: " + e.getMessage());
                    }
                }
            } finally {
                inProgressTasks.remove(sessionDest);
            }
        });
        t.setDaemon(true);
        t.setName("dingtalk-agent-" + Math.abs(sessionDest.hashCode() % 1000));
        t.start();
    }

    private void processAsyncMultimodal(String text, String imageUrl,
                                         String sessionWebhook, String senderNick,
                                         String sessionDest) {
        cancelInProgress(sessionDest);

        SessionKey key = SessionKey.of("dingtalk", sessionDest);
        sessionMirror.onInbound(key, "dingtalk", text != null ? text : "[image]");

        if (sessionWebhook != null) {
            sendTextReply(sessionWebhook, "正在分析图片...");
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);
        inProgressTasks.put(sessionDest, () -> {
            cancelled.set(true);
            gateway.interrupt(key);
        });

        Thread t = new Thread(() -> {
            try {
                Msg input = buildMultimodalMsg(text, imageUrl);
                var response = gateway.route(key, input).block(Duration.ofMinutes(5));

                if (!cancelled.get() && response != null && sessionWebhook != null) {
                    String reply = response.text();
                    reply = reply.replaceAll("<think>[\\s\\S]*?</think>\\s*", "");
                    if (reply.isBlank()) {
                        reply = "(No response)";
                    }
                    sendMarkdownReply(sessionWebhook, reply);
                    sessionMirror.onOutbound(key, "dingtalk", reply);
                    log.info("DingTalk image reply sent to [{}]: {}",
                            senderNick, reply.length() > 80 ? reply.substring(0, 80) + "..." : reply);
                }
            } catch (Exception e) {
                if (!cancelled.get()) {
                    log.error("Error processing DingTalk image message", e);
                    if (sessionWebhook != null) {
                        sendTextReply(sessionWebhook, "Error: " + e.getMessage());
                    }
                }
            } finally {
                inProgressTasks.remove(sessionDest);
            }
        });
        t.setDaemon(true);
        t.setName("dingtalk-image-" + Math.abs(sessionDest.hashCode() % 1000));
        t.start();
    }

    private void cancelInProgress(String sessionDest) {
        Runnable cancel = inProgressTasks.remove(sessionDest);
        if (cancel != null) {
            log.info("Interrupting in-progress DingTalk task for session [{}]", sessionDest);
            cancel.run();
        }
    }

    private void sendTextReply(String sessionWebhook, String content) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("msgtype", "text");
            ObjectNode text = body.putObject("text");
            text.put("content", content);
            doPost(sessionWebhook, body);
        } catch (Exception e) {
            log.error("Failed to send DingTalk text reply", e);
        }
    }

    private void sendMarkdownReply(String sessionWebhook, String content) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("msgtype", "markdown");
            ObjectNode md = body.putObject("markdown");
            md.put("title", "Reply");
            md.put("text", content);
            doPost(sessionWebhook, body);
        } catch (Exception e) {
            log.error("Failed to send DingTalk markdown reply", e);
        }
    }

    private void doPost(String url, ObjectNode body) {
        MessageDelivery.sendWithRetry(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, "dingtalk-reply");
    }

    private String extractText(JsonNode request) {
        JsonNode textNode = request.path("text");
        if (textNode.isObject()) {
            return textNode.path("content").asText("").trim();
        }
        return request.path("content").asText("").trim();
    }

    private String extractMsgType(JsonNode request) {
        return request.path("msgtype").asText("text");
    }

    private String extractImageUrl(JsonNode request) {
        JsonNode content = request.path("content");
        if (content.isObject()) {
            String url = content.path("downloadCode").asText(null);
            if (url != null && !url.isBlank()) return url;
            url = content.path("pictureDownloadUrl").asText(null);
            if (url != null && !url.isBlank()) return url;
        }
        String directUrl = request.path("pictureDownloadUrl").asText(null);
        if (directUrl != null && !directUrl.isBlank()) return directUrl;
        return request.path("imageUrl").asText(null);
    }

    private Msg buildMultimodalMsg(String text, String imageUrl) {
        var builder = Msg.builder().role(MsgRole.USER);
        if (imageUrl != null && !imageUrl.isBlank()) {
            builder.addContent(new Content.ImageContent(imageUrl, "image/jpeg", null));
        }
        String prompt = (text != null && !text.isBlank()) ? text : "请描述这张图片";
        builder.addContent(new Content.TextContent(prompt));
        return builder.build();
    }
}
