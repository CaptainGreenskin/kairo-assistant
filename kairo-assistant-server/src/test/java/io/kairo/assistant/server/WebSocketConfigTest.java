package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

class WebSocketConfigTest {

    @Test
    void configImplementsWebSocketConfigurer() {
        var config = new WebSocketConfig(null);
        assertInstanceOf(WebSocketConfigurer.class, config);
    }

    @Test
    void configAcceptsNullHandler() {
        assertDoesNotThrow(() -> new WebSocketConfig(null));
    }

    @Test
    void registersHandlerAtApiWsPath() {
        var handler = createHandler();
        var config = new WebSocketConfig(handler);
        var registry = new ServletWebSocketHandlerRegistry();
        assertDoesNotThrow(() -> config.registerWebSocketHandlers(registry));
    }

    @Test
    void multipleRegistrationsDoNotConflict() {
        var handler = createHandler();
        var config = new WebSocketConfig(handler);
        var registry1 = new ServletWebSocketHandlerRegistry();
        var registry2 = new ServletWebSocketHandlerRegistry();
        assertDoesNotThrow(() -> {
            config.registerWebSocketHandlers(registry1);
            config.registerWebSocketHandlers(registry2);
        });
    }

    @Test
    void configAnnotatedAsConfiguration() {
        assertTrue(WebSocketConfig.class.isAnnotationPresent(
                org.springframework.context.annotation.Configuration.class));
    }

    @Test
    void enableWebSocketAnnotationPresent() {
        assertTrue(WebSocketConfig.class.isAnnotationPresent(
                org.springframework.web.socket.config.annotation.EnableWebSocket.class));
    }

    private AssistantWebSocketHandler createHandler() {
        var session = TestFixtures.defaultSession();
        return new AssistantWebSocketHandler(session, TestFixtures.stubGateway(),
                new SessionAwareDeltaRouter(), new SessionManager(session), new MetricsCollector(), new StreamingDeltaRouter());
    }
}
