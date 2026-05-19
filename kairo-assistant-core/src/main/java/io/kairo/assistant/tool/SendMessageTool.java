package io.kairo.assistant.tool;

import io.kairo.api.channel.Channel;
import io.kairo.api.channel.ChannelIdentity;
import io.kairo.api.channel.ChannelMessage;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

@Tool(
        name = "send_message",
        description =
                "Send a message to a configured channel (DingTalk, Slack, Feishu, Telegram, etc). "
                        + "Use to proactively notify the user or push information to external systems.",
        category = ToolCategory.EXTERNAL,
        sideEffect = ToolSideEffect.SYSTEM_CHANGE)
public class SendMessageTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("channel", new JsonSchema("string", null, null,
                "Channel ID to send to (e.g. 'dingtalk', 'slack', 'feishu', 'telegram')."));
        props.put("message", new JsonSchema("string", null, null,
                "Message content to send."));
        props.put("destination", new JsonSchema("string", null, null,
                "Optional destination address within the channel (e.g. channel ID, group ID). "
                        + "Defaults to the channel's default destination."));
        return new JsonSchema("object", props, List.of("channel", "message"), null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.defer(() -> {
            String channelId = (String) args.get("channel");
            String message = (String) args.get("message");

            if (channelId == null || channelId.isBlank()) {
                return Mono.just(ToolResult.error("send_message", "Parameter 'channel' is required"));
            }
            if (message == null || message.isBlank()) {
                return Mono.just(ToolResult.error("send_message", "Parameter 'message' is required"));
            }

            String dest = (String) args.getOrDefault("destination", "default");

            // Try outbound router first (lightweight, server-registered senders)
            Object router = ctx.dependencies().get("outboundRouter");
            if (router instanceof OutboundRouter outbound) {
                boolean sent = outbound.send(channelId, dest, message);
                return Mono.just(sent
                        ? ToolResult.success("send_message",
                        "Message sent to " + channelId + "/" + dest)
                        : ToolResult.error("send_message",
                        "Failed to send to " + channelId + ". Available: "
                                + outbound.platforms()));
            }

            // Fallback to Channel SPI
            Map<String, Channel> channels =
                    (Map<String, Channel>) ctx.dependencies().get("channels");
            if (channels == null || channels.isEmpty()) {
                return Mono.just(ToolResult.error("send_message",
                        "No channels configured. Set up outboundRouter or channels dependency."));
            }

            Channel channel = channels.get(channelId);
            if (channel == null) {
                return Mono.just(ToolResult.error("send_message",
                        "Channel not found: " + channelId
                                + ". Available: " + channels.keySet()));
            }

            ChannelIdentity identity = ChannelIdentity.of(channelId, dest);
            ChannelMessage msg = ChannelMessage.of(identity, message);

            return channel.sender().send(msg)
                    .map(ack -> {
                        if (ack.success()) {
                            return ToolResult.success("send_message",
                                    "Message sent to " + channelId + " successfully");
                        } else {
                            return ToolResult.error("send_message",
                                    "Failed to send: " + ack.detail());
                        }
                    })
                    .onErrorResume(e -> Mono.just(ToolResult.error("send_message",
                            "Send failed: " + e.getMessage())));
        });
    }

    public interface OutboundRouter {
        boolean send(String platform, String destination, String message);
        java.util.Set<String> platforms();
    }
}
