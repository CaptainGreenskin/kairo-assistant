package io.kairo.assistant.server;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
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
        SseController controller = new SseController(null, new StreamingDeltaRouter());
        Map<String, Object> result = controller.send("test", Map.of("message", ""));
        assertEquals("message is required", result.get("error"));
    }

    @Test
    void sendRejectsMissingMessage() {
        SseController controller = new SseController(null, new StreamingDeltaRouter());
        Map<String, Object> result = controller.send("test", Map.of());
        assertEquals("message is required", result.get("error"));
    }

    @Test
    void sendRejectsUnconnectedClient() {
        SseController controller = new SseController(null, new StreamingDeltaRouter());
        Map<String, Object> result = controller.send("nobody", Map.of("message", "hello"));
        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("Not connected"));
    }

    @Test
    void connectReturnsFlux() {
        SseController controller = new SseController(TestFixtures.defaultSession(), new StreamingDeltaRouter());
        var flux = controller.connect("test-client");
        assertNotNull(flux);
    }

    @Test
    void connectEmitsConnectedEvent() {
        SseController controller = new SseController(TestFixtures.defaultSession(), new StreamingDeltaRouter());
        var events = controller.connect("test-client").take(1).collectList().block();
        assertNotNull(events);
        assertFalse(events.isEmpty());
        assertTrue(events.get(0).contains("connected"));
        assertTrue(events.get(0).contains("test-client"));
    }

    @Test
    void interruptReturnsStatus() {
        SseController controller = new SseController(TestFixtures.defaultSession(), new StreamingDeltaRouter());
        var result = controller.interrupt("test");
        assertEquals("interrupted", result.get("status"));
    }

    @Test
    void sendToUnsubscribedClientReturnsError() {
        var session = TestFixtures.defaultSession();
        SseController controller = new SseController(session, new StreamingDeltaRouter());
        var result = controller.send("not-connected", Map.of("message", "hello"));
        assertTrue(result.get("error").toString().contains("Not connected"));
    }

    @Test
    void disconnectRemovesClient() {
        SseController controller = new SseController(TestFixtures.defaultSession(), new StreamingDeltaRouter());
        Disposable sub = controller.connect("dc-client").subscribe();
        var result = controller.disconnect("dc-client");
        assertEquals("disconnected", result.get("status"));
        assertEquals("dc-client", result.get("clientId"));
        sub.dispose();
    }

    @Test
    void disconnectNotConnectedReturnsStatus() {
        SseController controller = new SseController(TestFixtures.defaultSession(), new StreamingDeltaRouter());
        var result = controller.disconnect("nobody");
        assertEquals("not_connected", result.get("status"));
    }

    @Test
    void listConnectionsEmpty() {
        SseController controller = new SseController(TestFixtures.defaultSession(), new StreamingDeltaRouter());
        var result = controller.listConnections();
        assertEquals(0, result.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listConnectionsShowsClients() {
        SseController controller = new SseController(TestFixtures.defaultSession(), new StreamingDeltaRouter());
        Disposable sub1 = controller.connect("c1").subscribe();
        Disposable sub2 = controller.connect("c2").subscribe();
        var result = controller.listConnections();
        assertEquals(2, result.get("count"));
        var ids = (java.util.List<String>) result.get("clientIds");
        assertTrue(ids.contains("c1"));
        assertTrue(ids.contains("c2"));
        sub1.dispose();
        sub2.dispose();
    }

    private SseController createController() {
        return new SseController(null, new StreamingDeltaRouter());
    }
}
