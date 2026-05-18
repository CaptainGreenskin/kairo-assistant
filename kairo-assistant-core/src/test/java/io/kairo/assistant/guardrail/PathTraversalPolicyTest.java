package io.kairo.assistant.guardrail;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.guardrail.GuardrailContext;
import io.kairo.api.guardrail.GuardrailDecision;
import io.kairo.api.guardrail.GuardrailPayload;
import io.kairo.api.guardrail.GuardrailPhase;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathTraversalPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void blocksPathTraversal() {
        var policy = new PathTraversalPolicy();
        var ctx = fileToolContext("read_file", "../../etc/passwd");
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.DENY, decision.action());
        assertTrue(decision.reason().contains("traversal"));
    }

    @Test
    void blocksSensitivePath() {
        var policy = new PathTraversalPolicy();
        var ctx = fileToolContext("read_file", "/etc/shadow");
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.DENY, decision.action());
        assertTrue(decision.reason().contains("sensitive"));
    }

    @Test
    void blocksOutsideWorkspace() {
        var policy = new PathTraversalPolicy(tempDir);
        var ctx = fileToolContext("write_file", "/tmp/outside.txt");
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.DENY, decision.action());
        assertTrue(decision.reason().contains("outside"));
    }

    @Test
    void allowsWithinWorkspace() {
        var policy = new PathTraversalPolicy(tempDir);
        var ctx = fileToolContext("read_file", tempDir.resolve("data.txt").toString());
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.ALLOW, decision.action());
    }

    @Test
    void allowsNoRoot() {
        var policy = new PathTraversalPolicy();
        var ctx = fileToolContext("read_file", "/tmp/safe.txt");
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.ALLOW, decision.action());
    }

    @Test
    void skipsNonFileTools() {
        var policy = new PathTraversalPolicy(tempDir);
        var ctx = new GuardrailContext(
                GuardrailPhase.PRE_TOOL, "assistant", "shell",
                new GuardrailPayload.ToolInput("shell", Map.of("command", "ls /etc")),
                Map.of());
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.ALLOW, decision.action());
    }

    @Test
    void hasTraversalComponentDetectsDoubleDot() {
        assertTrue(PathTraversalPolicy.hasTraversalComponent("../secret"));
        assertTrue(PathTraversalPolicy.hasTraversalComponent("/home/user/../root"));
        assertFalse(PathTraversalPolicy.hasTraversalComponent("/home/user/normal"));
        assertFalse(PathTraversalPolicy.hasTraversalComponent("file..name"));
    }

    @Test
    void allowsBlankPath() {
        var policy = new PathTraversalPolicy(tempDir);
        var ctx = new GuardrailContext(
                GuardrailPhase.PRE_TOOL, "assistant", "read_file",
                new GuardrailPayload.ToolInput("read_file", Map.of("file_path", "")),
                Map.of());
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.ALLOW, decision.action());
    }

    @Test
    void alternatePathKey() {
        var policy = new PathTraversalPolicy(tempDir);
        var ctx = new GuardrailContext(
                GuardrailPhase.PRE_TOOL, "assistant", "write_file",
                new GuardrailPayload.ToolInput("write_file", Map.of("path", "../../etc/passwd")),
                Map.of());
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.DENY, decision.action());
    }

    @Test
    void policyNameAndOrder() {
        var policy = new PathTraversalPolicy();
        assertEquals("PathTraversalPolicy", policy.name());
        assertEquals(-85, policy.order());
    }

    private GuardrailContext fileToolContext(String tool, String path) {
        return new GuardrailContext(
                GuardrailPhase.PRE_TOOL, "assistant", tool,
                new GuardrailPayload.ToolInput(tool, Map.of("file_path", path)),
                Map.of());
    }
}
