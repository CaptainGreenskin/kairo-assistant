package io.kairo.assistant.channel;

import io.kairo.api.agent.Agent;
import io.kairo.api.channel.Channel;
import io.kairo.api.channel.ChannelAck;
import io.kairo.api.channel.ChannelMessage;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Bridges a {@link Channel} to an {@link Agent}: inbound messages become agent calls, agent
 * responses become outbound replies.
 */
public class ChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(ChannelGateway.class);
    private static final int LOG_TRUNCATE_LENGTH = 100;

    private final Channel channel;
    private final Agent agent;

    public ChannelGateway(Channel channel, Agent agent) {
        this.channel = channel;
        this.agent = agent;
    }

    public Mono<Void> start() {
        return channel.start(this::handleInbound);
    }

    public Mono<Void> stop() {
        return channel.stop();
    }

    private Mono<ChannelAck> handleInbound(ChannelMessage message) {
        log.info(
                "Inbound from [{}] via [{}]: {}",
                message.identity().destination(),
                message.identity().channelId(),
                truncate(message.content(), LOG_TRUNCATE_LENGTH));

        return agent
                .call(Msg.of(MsgRole.USER, message.content()))
                .flatMap(response -> {
                    ChannelMessage reply =
                            ChannelMessage.of(message.identity(), response.text());
                    return channel.sender().send(reply);
                })
                .onErrorResume(e -> {
                    log.error("Error processing inbound message", e);
                    ChannelMessage errorReply =
                            ChannelMessage.of(
                                    message.identity(),
                                    "Sorry, I encountered an error: " + e.getMessage());
                    return channel.sender().send(errorReply);
                });
    }

    public Mono<ChannelAck> sendOutbound(ChannelMessage message) {
        log.info(
                "Outbound to [{}] via [{}]: {}",
                message.identity().destination(),
                message.identity().channelId(),
                truncate(message.content(), LOG_TRUNCATE_LENGTH));
        return channel.sender().send(message);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
