package io.kairo.assistant.guardrail;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.guardrail.GuardrailContext;
import io.kairo.api.guardrail.GuardrailDecision;
import io.kairo.api.guardrail.GuardrailPayload;
import io.kairo.api.guardrail.GuardrailPhase;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolLoopDetectionPolicyTest {

    private ToolLoopDetectionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ToolLoopDetectionPolicy(2, 4);
    }

    @Test
    void allowsFirstCall() {
        var ctx = preToolContext("shell", Map.of("command", "ls"));
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.ALLOW, decision.action());
    }

    @Test
    void warnsOnRepeatedCalls() {
        var ctx = preToolContext("shell", Map.of("command", "ls"));
        policy.evaluate(ctx).block();
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.WARN, decision.action());
        assertTrue(decision.reason().contains("2 times"));
    }

    @Test
    void blocksOnExcessiveRepeats() {
        var ctx = preToolContext("shell", Map.of("command", "ls"));
        policy.evaluate(ctx).block();
        policy.evaluate(ctx).block();
        policy.evaluate(ctx).block();
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.DENY, decision.action());
        assertTrue(decision.reason().contains("4 times"));
    }

    @Test
    void differentArgsDontTrigger() {
        policy.evaluate(preToolContext("shell", Map.of("command", "ls"))).block();
        policy.evaluate(preToolContext("shell", Map.of("command", "pwd"))).block();
        policy.evaluate(preToolContext("shell", Map.of("command", "date"))).block();
        var decision = policy.evaluate(preToolContext("shell", Map.of("command", "whoami"))).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.ALLOW, decision.action());
    }

    @Test
    void tracksConsecutiveFailures() {
        var errResult = ToolResult.error("shell", "command failed");
        var failCtx = postToolContext("shell", errResult);

        policy.evaluate(failCtx).block();
        policy.evaluate(failCtx).block();
        policy.evaluate(failCtx).block();
        var decision = policy.evaluate(failCtx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.WARN, decision.action());
        assertTrue(decision.reason().contains("failed"));
    }

    @Test
    void successResetsFailureCount() {
        var errResult = ToolResult.error("shell", "failed");
        var okResult = ToolResult.success("shell", "ok");

        policy.evaluate(postToolContext("shell", errResult)).block();
        policy.evaluate(postToolContext("shell", errResult)).block();
        policy.evaluate(postToolContext("shell", okResult)).block();
        var decision = policy.evaluate(postToolContext("shell", errResult)).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.ALLOW, decision.action());
    }

    @Test
    void resetClearsAllState() {
        var ctx = preToolContext("shell", Map.of("command", "ls"));
        policy.evaluate(ctx).block();
        policy.evaluate(ctx).block();
        policy.reset();
        var decision = policy.evaluate(ctx).block();
        assertNotNull(decision);
        assertEquals(GuardrailDecision.Action.ALLOW, decision.action());
    }

    private GuardrailContext preToolContext(String tool, Map<String, Object> args) {
        return new GuardrailContext(
                GuardrailPhase.PRE_TOOL, "assistant", tool,
                new GuardrailPayload.ToolInput(tool, args),
                Map.of());
    }

    private GuardrailContext postToolContext(String tool, ToolResult result) {
        return new GuardrailContext(
                GuardrailPhase.POST_TOOL, "assistant", tool,
                new GuardrailPayload.ToolOutput(tool, result),
                Map.of());
    }
}
