package io.kairo.assistant.security;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.api.tool.ToolSideEffect;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Scanner;
import org.junit.jupiter.api.Test;

class CliApprovalHandlerTest {

    @Test
    void approveOnYes() {
        var handler = handlerWithInput("y\n");
        var result = handler.requestApproval(request("shell")).block();
        assertNotNull(result);
        assertTrue(result.approved());
    }

    @Test
    void denyOnNo() {
        var handler = handlerWithInput("n\n");
        var result = handler.requestApproval(request("shell")).block();
        assertNotNull(result);
        assertFalse(result.approved());
    }

    @Test
    void alwaysRemembersForSession() {
        var handler = handlerWithInput("a\ny\n");

        var first = handler.requestApproval(request("shell")).block();
        assertNotNull(first);
        assertTrue(first.approved());

        var second = handler.requestApproval(request("shell")).block();
        assertNotNull(second);
        assertTrue(second.approved());
    }

    @Test
    void alwaysIsPerTool() {
        var handler = handlerWithInput("a\nn\n");

        handler.requestApproval(request("shell")).block();

        var other = handler.requestApproval(request("code_execute")).block();
        assertNotNull(other);
        assertFalse(other.approved());
    }

    @Test
    void resetClearsSessionApprovals() {
        var handler = handlerWithInput("a\nn\n");
        handler.requestApproval(request("shell")).block();

        handler.resetSessionApprovals();

        var result = handler.requestApproval(request("shell")).block();
        assertNotNull(result);
        assertFalse(result.approved());
    }

    @Test
    void outputShowsToolInfo() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var handler = new CliApprovalHandler(new Scanner("y\n"), out);

        handler.requestApproval(request("shell")).block();
        String output = baos.toString();

        assertTrue(output.contains("shell"));
        assertTrue(output.contains("SYSTEM_CHANGE"));
    }

    @Test
    void denyReasonContainsToolName() {
        var handler = handlerWithInput("n\n");
        var result = handler.requestApproval(request("dangerous_tool")).block();
        assertNotNull(result);
        assertFalse(result.approved());
        assertTrue(result.reason().contains("dangerous_tool"));
    }

    @Test
    void emptyInputDenies() {
        var handler = handlerWithInput("");
        var result = handler.requestApproval(request("shell")).block();
        assertNotNull(result);
        assertFalse(result.approved());
    }

    @Test
    void outputShowsArgs() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var handler = new CliApprovalHandler(new Scanner("y\n"), out);

        handler.requestApproval(
                new ToolCallRequest("shell", Map.of("command", "rm -rf /"), ToolSideEffect.SYSTEM_CHANGE)).block();
        String output = baos.toString();
        assertTrue(output.contains("command"));
    }

    @Test
    void unknownInputDefaultsToDeny() {
        var handler = handlerWithInput("maybe\n");
        var result = handler.requestApproval(request("shell")).block();
        assertNotNull(result);
        assertFalse(result.approved());
    }

    private CliApprovalHandler handlerWithInput(String input) {
        return new CliApprovalHandler(
                new Scanner(input),
                new PrintStream(new ByteArrayOutputStream()));
    }

    private ToolCallRequest request(String tool) {
        return new ToolCallRequest(tool, Map.of("command", "echo hi"), ToolSideEffect.SYSTEM_CHANGE);
    }
}
