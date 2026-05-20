package io.kairo.assistant.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AssistantWebSocketHandler handler;
    private final MirrorWebSocketHandler mirrorHandler;

    public WebSocketConfig(AssistantWebSocketHandler handler,
                           MirrorWebSocketHandler mirrorHandler) {
        this.handler = handler;
        this.mirrorHandler = mirrorHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ws")
                .setAllowedOrigins("*");
        registry.addHandler(mirrorHandler, "/api/mirror")
                .setAllowedOrigins("*");
    }
}
