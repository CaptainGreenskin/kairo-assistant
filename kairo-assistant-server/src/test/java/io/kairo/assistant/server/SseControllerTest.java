package io.kairo.assistant.server;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SseControllerTest {

    @Test
    void escapeJsonHandlesSpecialChars() {
        assertEquals("hello\\nworld", JsonEscape.escape("hello\nworld"));
        assertEquals("say \\\"hi\\\"", JsonEscape.escape("say \"hi\""));
        assertEquals("back\\\\slash", JsonEscape.escape("back\\slash"));
        assertEquals("line\\r\\nbreak", JsonEscape.escape("line\r\nbreak"));
    }

    @Test
    void escapeJsonHandlesNull() {
        assertEquals("", JsonEscape.escape(null));
    }

    @Test
    void sseEventFormatsCorrectly() throws Exception {
        SseController controller = createController();
        Method sseEvent = SseController.class.getDeclaredMethod("sseEvent", String.class, Map.class);
        sseEvent.setAccessible(true);

        String result = (String) sseEvent.invoke(controller, "connected", Map.of("clientId", "abc"));
        assertTrue(result.startsWith("data: "));
        assertTrue(result.contains("\"type\":\"connected\""));
        assertTrue(result.contains("\"clientId\":\"abc\""));
        assertTrue(result.endsWith("\n\n"));
    }

    @Test
    void sseEventWithEmptyData() throws Exception {
        SseController controller = createController();
        Method sseEvent = SseController.class.getDeclaredMethod("sseEvent", String.class, Map.class);
        sseEvent.setAccessible(true);

        String result = (String) sseEvent.invoke(controller, "done", Map.of());
        assertEquals("data: {\"type\":\"done\"}\n\n", result);
    }

    @Test
    void sendRejectsBlankMessage() {
        SseController controller = new SseController(null);
        Map<String, Object> result = controller.send("test", Map.of("message", ""));
        assertEquals("message is required", result.get("error"));
    }

    @Test
    void sendRejectsMissingMessage() {
        SseController controller = new SseController(null);
        Map<String, Object> result = controller.send("test", Map.of());
        assertEquals("message is required", result.get("error"));
    }

    @Test
    void sendRejectsUnconnectedClient() {
        SseController controller = new SseController(null);
        Map<String, Object> result = controller.send("nobody", Map.of("message", "hello"));
        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("Not connected"));
    }

    @Test
    void connectReturnsFlux() {
        SseController controller = new SseController(TestFixtures.defaultSession());
        var flux = controller.connect("test-client");
        assertNotNull(flux);
    }

    @Test
    void connectEmitsConnectedEvent() {
        SseController controller = new SseController(TestFixtures.defaultSession());
        var events = controller.connect("test-client").take(1).collectList().block();
        assertNotNull(events);
        assertFalse(events.isEmpty());
        assertTrue(events.get(0).contains("connected"));
        assertTrue(events.get(0).contains("test-client"));
    }

    @Test
    void interruptReturnsStatus() {
        SseController controller = new SseController(TestFixtures.defaultSession());
        var result = controller.interrupt("test");
        assertEquals("interrupted", result.get("status"));
    }

    @Test
    void sendToUnsubscribedClientReturnsError() {
        var session = TestFixtures.defaultSession();
        SseController controller = new SseController(session);
        var result = controller.send("not-connected", Map.of("message", "hello"));
        assertTrue(result.get("error").toString().contains("Not connected"));
    }

    private SseController createController() {
        return new SseController(null);
    }
}
