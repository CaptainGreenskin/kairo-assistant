package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunControllerTest {

    private RunController controller;
    private RunRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RunRegistry();
        controller = new RunController(
                TestFixtures.stubGateway(), new SessionAwareDeltaRouter(), registry,
                new SessionRunQueue(), TestFixtures.defaultSession());
    }

    @Test
    void createRejectsBlankInput() {
        var resp = controller.create(new RunController.RunRequest("  ", null));
        assertEquals(400, resp.getStatusCode().value());
        assertEquals("input is required", resp.getBody().get("error"));
    }

    @Test
    void createRejectsNullBody() {
        var resp = controller.create(null);
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void getUnknownReturns404() {
        var resp = controller.get("missing");
        assertEquals(404, resp.getStatusCode().value());
        assertEquals("run not found", resp.getBody().get("error"));
    }

    @Test
    void stopUnknownReturns404() {
        var resp = controller.stop("missing");
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void eventsUnknownReturnsErrorFrame() {
        String frame = controller.events("missing").blockFirst();
        assertNotNull(frame);
        assertTrue(frame.contains("run not found"));
    }

    @Test
    void steerUnknownReturns404() {
        var resp = controller.steer("missing", new RunController.RunRequest("hi", null));
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void steerBlankInputReturns400() {
        registry.create("r1", io.kairo.core.session.SessionKey.of("run", "r1"));
        var resp = controller.steer("r1", new RunController.RunRequest("  ", null));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void steerNonRunningReturns409() {
        registry.create("r2", io.kairo.core.session.SessionKey.of("run", "r2"));
        // status is PENDING (not RUNNING)
        var resp = controller.steer("r2", new RunController.RunRequest("nudge", null));
        assertEquals(409, resp.getStatusCode().value());
    }

    @Test
    void handoffRequiresSourceSessionId() {
        var resp = controller.handoff(new RunController.HandoffRequest("  ", null));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void handoffUnknownSourceReturns404() {
        var resp = controller.handoff(new RunController.HandoffRequest("no-such-session", null));
        assertEquals(404, resp.getStatusCode().value());
    }
}
