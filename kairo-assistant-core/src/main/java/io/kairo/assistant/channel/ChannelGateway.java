/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.channel;

import io.kairo.api.agent.Agent;
import io.kairo.api.gateway.Channel;
import io.kairo.api.gateway.ChannelMessage;
import io.kairo.api.gateway.DeliveryTarget;
import io.kairo.api.gateway.SendResult;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Bridges a single {@link Channel} to an {@link Agent}: inbound messages become agent calls,
 * agent responses become outbound replies via {@link Channel#send}.
 *
 * <p>Post-collapse this is a thin convenience wrapper. Applications that need multi-channel
 * orchestration should use the full {@link io.kairo.api.gateway.Gateway} from kairo-gateway
 * instead — this class is kept for the assistant's {@code /api/channels} REST surface, which
 * runs one channel per gateway.
 */
public class ChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(ChannelGateway.class);
    private static final int LOG_TRUNCATE_LENGTH = 100;

    private final Channel channel;
    private final Agent agent;
    private volatile Disposable inboundSubscription;

    public ChannelGateway(Channel channel, Agent agent) {
        this.channel = channel;
        this.agent = agent;
    }

    public Mono<Void> start() {
        return channel.connect()
                .doOnSuccess(
                        v ->
                                inboundSubscription =
                                        channel.inbound()
                                                .flatMap(this::handleInbound)
                                                .subscribe());
    }

    public Mono<Void> stop() {
        if (inboundSubscription != null) inboundSubscription.dispose();
        return channel.disconnect();
    }

    private Mono<SendResult> handleInbound(ChannelMessage message) {
        log.info(
                "Inbound from [{}] via [{}]: {}",
                message.source().chatId(),
                message.source().channelId(),
                truncate(message.text(), LOG_TRUNCATE_LENGTH));

        DeliveryTarget back = DeliveryTarget.origin(message.source());
        return agent.call(Msg.of(MsgRole.USER, message.text()))
                .flatMap(
                        response -> channel.send(back, response.text(), message.messageId(), Map.of()))
                .onErrorResume(
                        e -> {
                            log.error("Error processing inbound message", e);
                            return channel.send(
                                    back,
                                    "Sorry, I encountered an error: " + e.getMessage(),
                                    message.messageId(),
                                    Map.of());
                        });
    }

    public Mono<SendResult> sendOutbound(DeliveryTarget target, String content) {
        log.info(
                "Outbound to [{}] via [{}]: {}",
                target.chatId(),
                target.channelId(),
                truncate(content, LOG_TRUNCATE_LENGTH));
        return channel.send(target, content, null, Map.of());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
