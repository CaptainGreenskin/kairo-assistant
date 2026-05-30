package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.core.session.SessionKey;
import org.junit.jupiter.api.Test;

class RunRegistryTest {

    private final RunRegistry registry = new RunRegistry();

    private RunRegistry.Run newRun(String id) {
        return registry.create(id, SessionKey.of("run", id));
    }

    @Test
    void createStartsPending() {
        var run = newRun("r1");
        assertEquals(RunRegistry.Status.PENDING, run.status());
        assertEquals("pending", registry.snapshot(run).get("status"));
    }

    @Test
    void markRunningTransitions() {
        newRun("r2");
        registry.markRunning("r2");
        assertEquals(RunRegistry.Status.RUNNING, registry.get("r2").status());
    }

    @Test
    void succeedRecordsResultAndIsTerminal() {
        newRun("r3");
        registry.markRunning("r3");
        registry.succeed("r3", "the answer");
        var snap = registry.snapshot(registry.get("r3"));
        assertEquals("succeeded", snap.get("status"));
        assertEquals("the answer", snap.get("result"));
        // terminal: a later fail must not override
        registry.fail("r3", "late error");
        assertEquals(RunRegistry.Status.SUCCEEDED, registry.get("r3").status());
    }

    @Test
    void failRecordsError() {
        newRun("r4");
        registry.fail("r4", "boom");
        var snap = registry.snapshot(registry.get("r4"));
        assertEquals("failed", snap.get("status"));
        assertEquals("boom", snap.get("error"));
    }

    @Test
    void stopReturnsTrueOnceThenFalse() {
        newRun("r5");
        registry.markRunning("r5");
        assertTrue(registry.stop("r5"));
        assertEquals(RunRegistry.Status.STOPPED, registry.get("r5").status());
        assertFalse(registry.stop("r5"));
    }

    @Test
    void unknownRunHasNoEventsStream() {
        assertNull(registry.events("nope"));
        assertNull(registry.get("nope"));
    }
}
