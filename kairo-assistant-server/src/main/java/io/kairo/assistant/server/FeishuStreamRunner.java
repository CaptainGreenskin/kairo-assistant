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
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "feishu-patch");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Runnable> inProgressTasks = new ConcurrentHashMap<>();

    public FeishuStreamRunner(UnifiedGateway gateway, SessionAwareDeltaRouter sessionDeltaRouter) {
        this.gateway = gateway;
        this.sessionDeltaRouter = sessionDeltaRouter;
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
                String msgType = eventData.getMessage().getMessageType();
                String senderOpenId = eventData.getSender() != null
                        ? eventData.getSender().getSenderId().getOpenId() : "unknown";

                String text = extractText(eventData.getMessage().getContent(), msgType);
                if (text == null || text.isBlank()) {
                    log.debug("Ignoring empty Feishu message");
                    return;
                }

                log.info("Feishu inbound from [{}] in chat [{}]: {}", senderOpenId, chatId,
                        text.length() > 100 ? text.substring(0, 100) + "..." : text);

                cancelInProgress(chatId);

                String messageId = sendInitialMessage(apiClient, chatId, "正在思考...");
                if (messageId == null) {
                    log.warn("Failed to create initial Feishu message, falling back to blocking mode");
                    fallbackBlockingReply(apiClient, chatId, text);
                    return;
                }

                streamingReply(apiClient, chatId, messageId, text);

            } catch (Exception e) {
                log.error("Error processing Feishu message", e);
            }
        });
        t.setDaemon(true);
        t.setName("feishu-agent");
        t.start();
    }

    private void streamingReply(Client apiClient, String chatId, String messageId, String text) {
        ThinkBlockFilter filter = new ThinkBlockFilter();
        StringBuilder accumulated = new StringBuilder();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        SessionKey key = SessionKey.of("feishu", chatId);
        String subId = "feishu-" + chatId + "-" + System.nanoTime();

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

        inProgressTasks.put(chatId, () -> {
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
            inProgressTasks.remove(chatId);
            patchFuture.cancel(false);
            sessionDeltaRouter.unsubscribe(key, subId);
        }
    }

    private void fallbackBlockingReply(Client apiClient, String chatId, String text) {
        try {
            SessionKey key = SessionKey.of("feishu", chatId);
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

    private void cancelInProgress(String chatId) {
        Runnable cancel = inProgressTasks.remove(chatId);
        if (cancel != null) {
            log.info("Interrupting in-progress Feishu task for chat [{}]", chatId);
            cancel.run();
        }
    }

    private String sendInitialMessage(Client apiClient, String chatId, String content) {
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
            log.warn("Feishu create message failed: code={}, msg={}",
                    resp != null ? resp.getCode() : "null",
                    resp != null ? resp.getMsg() : "null");
            return null;
        } catch (Exception e) {
            log.error("Failed to create Feishu message", e);
            return null;
        }
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
