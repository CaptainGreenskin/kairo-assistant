package io.kairo.assistant.server;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Starts a DingTalk Stream Mode connection on application startup if DINGTALK_CLIENT_ID and
 * DINGTALK_CLIENT_SECRET environment variables are set.
 *
 * <p>Stream Mode establishes a WebSocket from the client side — no public IP needed.
 */
@Component
public class DingTalkStreamRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamRunner.class);
    private static final String ROBOT_MSG_TOPIC = "/v1.0/im/bot/messages/get";

    private final AssistantSession session;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public DingTalkStreamRunner(AssistantSession session) {
        this.session = session;
    }

    @Override
    public void run(String... args) {
        String clientId = System.getenv("DINGTALK_CLIENT_ID");
        String clientSecret = System.getenv("DINGTALK_CLIENT_SECRET");

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            log.info("DingTalk Stream Mode disabled (DINGTALK_CLIENT_ID/SECRET not set)");
            return;
        }

        try {
            OpenDingTalkClient client = OpenDingTalkStreamClientBuilder.custom()
                    .credential(new AuthClientCredential(clientId, clientSecret))
                    .registerCallbackListener(ROBOT_MSG_TOPIC, new RobotMessageListener())
                    .build();
            client.start();
            log.info("DingTalk Stream Mode connected (clientId={}...)", clientId.substring(0, Math.min(8, clientId.length())));
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
                String sessionWebhook = request.path("sessionWebhook").asText(null);
                String senderNick = request.path("senderNick").asText("user");
                String conversationId = request.path("conversationId").asText("unknown");

                if (text == null || text.isBlank()) {
                    log.debug("Ignoring empty DingTalk message");
                    return "{}";
                }

                log.info("DingTalk inbound from [{}] in [{}]: {}", senderNick, conversationId,
                        text.length() > 100 ? text.substring(0, 100) + "..." : text);

                // Process asynchronously so we ACK the stream immediately
                processAsync(text, sessionWebhook, senderNick);

            } catch (Exception e) {
                log.error("Error handling DingTalk stream message", e);
            }
            return "{}";
        }
    }

    private void processAsync(String text, String sessionWebhook, String senderNick) {
        Thread t = new Thread(() -> {
            try {
                Agent agent = session.agent();
                var response = agent.call(Msg.of(MsgRole.USER, text)).block(Duration.ofMinutes(5));

                if (response != null && sessionWebhook != null) {
                    String reply = response.text();
                    reply = reply.replaceAll("<think>[\\s\\S]*?</think>\\s*", "");
                    sendReply(sessionWebhook, reply);
                    log.info("DingTalk reply sent to [{}]: {}",
                            senderNick, reply.length() > 80 ? reply.substring(0, 80) + "..." : reply);
                }
            } catch (Exception e) {
                log.error("Error processing DingTalk message", e);
                if (sessionWebhook != null) {
                    sendReply(sessionWebhook, "Sorry, I encountered an error: " + e.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.setName("dingtalk-agent");
        t.start();
    }

    private void sendReply(String sessionWebhook, String content) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("msgtype", "text");
            ObjectNode text = body.putObject("text");
            text.put("content", content);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sessionWebhook))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("DingTalk reply failed: HTTP {} — {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Failed to send DingTalk reply", e);
        }
    }

    private String extractText(JsonNode request) {
        JsonNode textNode = request.path("text");
        if (textNode.isObject()) {
            return textNode.path("content").asText("").trim();
        }
        return request.path("content").asText("").trim();
    }
}
