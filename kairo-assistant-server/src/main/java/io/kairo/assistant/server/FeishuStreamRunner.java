package io.kairo.assistant.server;

import com.lark.oapi.Client;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.PatchMessageReqBody;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.gateway.ModelSwitchService;
import io.kairo.assistant.gateway.SessionKey;
import io.kairo.assistant.gateway.UnifiedGateway;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class FeishuStreamRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FeishuStreamRunner.class);
    private static final long PATCH_INTERVAL_MS = 1500;

    private final UnifiedGateway gateway;
    private final SessionAwareDeltaRouter sessionDeltaRouter;
    private final ModelSwitchService modelSwitchService;
    private final OutboundMessageRouter outboundRouter;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "feishu-patch");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Runnable> inProgressTasks = new ConcurrentHashMap<>();

    public FeishuStreamRunner(UnifiedGateway gateway,
                              SessionAwareDeltaRouter sessionDeltaRouter,
                              ModelSwitchService modelSwitchService,
                              OutboundMessageRouter outboundRouter) {
        this.gateway = gateway;
        this.sessionDeltaRouter = sessionDeltaRouter;
        this.modelSwitchService = modelSwitchService;
        this.outboundRouter = outboundRouter;
    }

    @Override
    public void run(String... args) {
        String appId = System.getenv("FEISHU_APP_ID");
        String appSecret = System.getenv("FEISHU_APP_SECRET");

        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            log.info("Feishu WebSocket Mode disabled (FEISHU_APP_ID/SECRET not set)");
            return;
        }

        try {
            Client apiClient = Client.newBuilder(appId, appSecret).build();

            outboundRouter.register("feishu", (destination, message) -> {
                try {
                    sendInitialMessage(apiClient, destination, message);
                    return true;
                } catch (Exception e) {
                    log.error("Feishu outbound send failed to [{}]: {}", destination, e.getMessage());
                    return false;
                }
            });

            EventDispatcher eventDispatcher = EventDispatcher.newBuilder("", "")
                    .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(P2MessageReceiveV1 event) {
                            processMessageEvent(apiClient, event);
                        }
                    })
                    .build();

            com.lark.oapi.ws.Client wsClient = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                    .eventHandler(eventDispatcher)
                    .build();
            wsClient.start();

            log.info("Feishu WebSocket Mode connected (appId={}...)",
                    appId.substring(0, Math.min(8, appId.length())));
        } catch (Exception e) {
            log.error("Failed to start Feishu WebSocket Mode: {}", e.getMessage(), e);
        }
    }

    private void processMessageEvent(Client apiClient, P2MessageReceiveV1 event) {
        Thread t = new Thread(() -> {
            try {
                var eventData = event.getEvent();
                if (eventData == null || eventData.getMessage() == null) {
                    return;
                }

                String chatId = eventData.getMessage().getChatId();
                String chatType = eventData.getMessage().getChatType();
                String msgType = eventData.getMessage().getMessageType();
                String senderOpenId = eventData.getSender() != null
                        ? eventData.getSender().getSenderId().getOpenId() : "unknown";
                boolean isGroup = "group".equals(chatType);

                String messageId_event = eventData.getMessage().getMessageId();
                String imageKey = null;

                if ("image".equals(msgType)) {
                    imageKey = extractImageKey(eventData.getMessage().getContent());
                    if (imageKey == null) {
                        log.debug("Ignoring image message with no image_key");
                        return;
                    }
                }

                String text = extractText(eventData.getMessage().getContent(), msgType);
                if (imageKey == null && (text == null || text.isBlank())) {
                    log.debug("Ignoring empty Feishu message");
                    return;
                }

                if (isGroup && !isBotMentioned(eventData.getMessage().getMentions())) {
                    log.debug("Ignoring group message without @mention from [{}]", senderOpenId);
                    return;
                }

                if (isGroup) {
                    text = stripMentionTags(text);
                }

                String sessionDest = isGroup
                        ? chatId + ":" + senderOpenId
                        : chatId;

                log.info("Feishu inbound from [{}] in chat [{}]({}): {}", senderOpenId, chatId,
                        isGroup ? "group" : "p2p",
                        text.length() > 100 ? text.substring(0, 100) + "..." : text);

                if (text.startsWith("/model")) {
                    SessionKey key = SessionKey.of("feishu", sessionDest);
                    String modelId = text.substring("/model".length()).trim();
                    String reply;
                    if (modelId.isEmpty()) {
                        reply = "Available models: " + String.join(", ",
                                modelSwitchService.registry().aliases());
                    } else {
                        ModelSwitchService.SwitchResult result =
                                modelSwitchService.switchModel(key, modelId);
                        reply = result.message();
                    }
                    sendInitialMessage(apiClient, chatId, reply);
                    return;
                }

                cancelInProgress(sessionDest);

                if (imageKey != null) {
                    processImageMessage(apiClient, chatId, messageId_event, imageKey, text, sessionDest);
                    return;
                }

                String messageId = sendInitialMessage(apiClient, chatId, "正在思考...");
                if (messageId == null) {
                    log.warn("Failed to create initial Feishu message, falling back to blocking mode");
                    fallbackBlockingReply(apiClient, chatId, text, sessionDest);
                    return;
                }

                streamingReply(apiClient, chatId, messageId, text, sessionDest);

            } catch (Exception e) {
                log.error("Error processing Feishu message", e);
            }
        });
        t.setDaemon(true);
        t.setName("feishu-agent");
        t.start();
    }

    private boolean isBotMentioned(com.lark.oapi.service.im.v1.model.MentionEvent[] mentions) {
        return mentions != null && mentions.length > 0;
    }

    private String stripMentionTags(String text) {
        return text.replaceAll("@_user_\\d+", "").trim();
    }

    private void streamingReply(Client apiClient, String chatId, String messageId,
                               String text, String sessionDest) {
        ThinkBlockFilter filter = new ThinkBlockFilter();
        StringBuilder accumulated = new StringBuilder();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        SessionKey key = SessionKey.of("feishu", sessionDest);
        String subId = "feishu-" + sessionDest + "-" + System.nanoTime();

        sessionDeltaRouter.subscribe(key, subId, delta -> {
            String visible = filter.filter(delta);
            if (!visible.isEmpty()) {
                synchronized (accumulated) {
                    accumulated.append(visible);
                }
            }
        });

        ScheduledFuture<?> patchFuture = scheduler.scheduleAtFixedRate(() -> {
            String content;
            synchronized (accumulated) {
                content = accumulated.toString();
            }
            if (!content.isBlank()) {
                patchMessage(apiClient, messageId, content + " ▍");
            }
        }, PATCH_INTERVAL_MS, PATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        inProgressTasks.put(sessionDest, () -> {
            cancelled.set(true);
            gateway.interrupt(key);
            patchFuture.cancel(false);
            sessionDeltaRouter.unsubscribe(key, subId);
        });

        try {
            var response = gateway.route(key, Msg.of(MsgRole.USER, text))
                    .block(Duration.ofMinutes(5));

            patchFuture.cancel(false);
            sessionDeltaRouter.unsubscribe(key, subId);
            String flushed = filter.flush();
            synchronized (accumulated) {
                accumulated.append(flushed);
            }

            if (!cancelled.get() && response != null) {
                String finalText;
                synchronized (accumulated) {
                    finalText = accumulated.toString();
                }
                if (finalText.isBlank()) {
                    finalText = response.text().replaceAll("<think>[\\s\\S]*?</think>\\s*", "");
                }
                if (finalText.isBlank()) {
                    finalText = "(No response)";
                }
                patchMessage(apiClient, messageId, finalText);
                log.info("Feishu reply sent to chat [{}]: {}",
                        chatId, finalText.length() > 80 ? finalText.substring(0, 80) + "..." : finalText);
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                log.error("Error processing Feishu message", e);
                patchMessage(apiClient, messageId, "Error: " + e.getMessage());
            }
        } finally {
            inProgressTasks.remove(sessionDest);
            patchFuture.cancel(false);
            sessionDeltaRouter.unsubscribe(key, subId);
        }
    }

    private void fallbackBlockingReply(Client apiClient, String chatId, String text,
                                       String sessionDest) {
        try {
            SessionKey key = SessionKey.of("feishu", sessionDest);
            var response = gateway.route(key, Msg.of(MsgRole.USER, text))
                    .block(Duration.ofMinutes(5));
            if (response != null) {
                String reply = response.text().replaceAll("<think>[\\s\\S]*?</think>\\s*", "");
                sendInitialMessage(apiClient, chatId, reply.isBlank() ? "(No response)" : reply);
            }
        } catch (Exception e) {
            log.error("Feishu fallback reply failed", e);
        }
    }

    private void cancelInProgress(String sessionDest) {
        Runnable cancel = inProgressTasks.remove(sessionDest);
        if (cancel != null) {
            log.info("Interrupting in-progress Feishu task for session [{}]", sessionDest);
            cancel.run();
        }
    }

    private String sendInitialMessage(Client apiClient, String chatId, String content) {
        return MessageDelivery.sendWithRetry(() -> {
            try {
                String textContent = "{\"text\":\"" + escapeJson(content) + "\"}";

                CreateMessageReq req = CreateMessageReq.newBuilder()
                        .receiveIdType("chat_id")
                        .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                .receiveId(chatId)
                                .msgType("text")
                                .content(textContent)
                                .build())
                        .build();

                CreateMessageResp resp = apiClient.im().message().create(req);
                if (resp != null && resp.success() && resp.getData() != null) {
                    return resp.getData().getMessageId();
                }
                throw new RuntimeException("Feishu create failed: code=" +
                        (resp != null ? resp.getCode() : "null"));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, "feishu-create-message");
    }

    private void patchMessage(Client apiClient, String messageId, String content) {
        try {
            String textContent = "{\"text\":\"" + escapeJson(content) + "\"}";

            PatchMessageReq req = PatchMessageReq.newBuilder()
                    .messageId(messageId)
                    .patchMessageReqBody(PatchMessageReqBody.newBuilder()
                            .content(textContent)
                            .build())
                    .build();

            apiClient.im().message().patch(req);
        } catch (Exception e) {
            log.debug("Feishu patch message failed (may be deleted): {}", e.getMessage());
        }
    }

    private void processImageMessage(Client apiClient, String chatId, String eventMessageId,
                                      String imageKey, String text, String sessionDest) {
        String replyMsgId = sendInitialMessage(apiClient, chatId, "正在分析图片...");

        SessionKey key = SessionKey.of("feishu", sessionDest);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        inProgressTasks.put(sessionDest, () -> {
            cancelled.set(true);
            gateway.interrupt(key);
        });

        Thread t = new Thread(() -> {
            try {
                byte[] imageBytes = downloadImage(apiClient, eventMessageId, imageKey);
                if (imageBytes == null || imageBytes.length == 0) {
                    String errorMsg = "Failed to download image from Feishu";
                    if (replyMsgId != null) {
                        patchMessage(apiClient, replyMsgId, errorMsg);
                    } else {
                        sendInitialMessage(apiClient, chatId, errorMsg);
                    }
                    return;
                }

                Msg input = buildMultimodalMsg(text, imageBytes);
                var response = gateway.route(key, input).block(Duration.ofMinutes(5));

                if (!cancelled.get() && response != null) {
                    String reply = response.text().replaceAll("<think>[\\s\\S]*?</think>\\s*", "");
                    if (reply.isBlank()) {
                        reply = "(No response)";
                    }
                    if (replyMsgId != null) {
                        patchMessage(apiClient, replyMsgId, reply);
                    } else {
                        sendInitialMessage(apiClient, chatId, reply);
                    }
                    log.info("Feishu image reply sent to chat [{}]: {}",
                            chatId, reply.length() > 80 ? reply.substring(0, 80) + "..." : reply);
                }
            } catch (Exception e) {
                if (!cancelled.get()) {
                    log.error("Error processing Feishu image message", e);
                    String errorMsg = "Error: " + e.getMessage();
                    if (replyMsgId != null) {
                        patchMessage(apiClient, replyMsgId, errorMsg);
                    } else {
                        sendInitialMessage(apiClient, chatId, errorMsg);
                    }
                }
            } finally {
                inProgressTasks.remove(sessionDest);
            }
        });
        t.setDaemon(true);
        t.setName("feishu-image-" + Math.abs(sessionDest.hashCode() % 1000));
        t.start();
    }

    private byte[] downloadImage(Client apiClient, String messageId, String imageKey) {
        try {
            GetMessageResourceReq req = GetMessageResourceReq.newBuilder()
                    .messageId(messageId)
                    .fileKey(imageKey)
                    .type("image")
                    .build();

            GetMessageResourceResp resp = apiClient.im().messageResource().get(req);
            if (resp != null && resp.success() && resp.getData() != null) {
                return resp.getData().toByteArray();
            }
            log.warn("Feishu image download failed: code={}, msg={}",
                    resp != null ? resp.getCode() : "null",
                    resp != null ? resp.getMsg() : "null");
        } catch (Exception e) {
            log.error("Failed to download Feishu image [{}]: {}", imageKey, e.getMessage());
        }
        return null;
    }

    private String extractImageKey(String content) {
        if (content == null) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
            return node.path("image_key").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Msg buildMultimodalMsg(String text, byte[] imageBytes) {
        var builder = Msg.builder().role(MsgRole.USER);
        builder.addContent(new Content.ImageContent(null, "image/jpeg", imageBytes));
        String prompt = (text != null && !text.isBlank()) ? text : "请描述这张图片";
        builder.addContent(new Content.TextContent(prompt));
        return builder.build();
    }

    private String extractText(String content, String msgType) {
        if (content == null) return null;
        if (!"text".equals(msgType)) {
            log.debug("Ignoring non-text Feishu message type: {}", msgType);
            return null;
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
            return node.path("text").asText("").trim();
        } catch (Exception e) {
            return content.trim();
        }
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
