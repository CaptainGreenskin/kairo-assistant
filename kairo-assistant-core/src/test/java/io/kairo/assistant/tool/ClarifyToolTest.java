package io.kairo.assistant.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClarifyToolTest {

    private ClarifyTool tool;

    @BeforeEach
    void setUp() {
        tool = new ClarifyTool();
    }

    @Test
    void requiresQuestion() {
        ToolResult result = tool.execute(Map.of(), ctx()).block();
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void returnsFormattedQuestion() {
        ToolResult result = tool.execute(
                Map.of("question", "Which database should we use?"), ctx()).block();
        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().contains("Which database"));
    }

    @Test
    void includesOptionsWhenProvided() {
        ToolResult result = tool.execute(Map.of(
                "question", "Pick a color",
                "options", "Red, Blue, Green"), ctx()).block();
        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().contains("1. Red"));
        assertTrue(result.content().contains("2. Blue"));
        assertTrue(result.content().contains("3. Green"));
    }

    @Test
    void includesContext() {
        ToolResult result = tool.execute(Map.of(
                "question", "Should we proceed?",
                "context", "The migration will affect 5M rows"), ctx()).block();
        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().contains("5M rows"));
    }

    @Test
    void schemaHasRequired() {
        var schema = tool.inputSchema();
        assertTrue(schema.required().contains("question"));
    }

    private ToolContext ctx() {
        return new ToolContext("test", "s1", Map.of());
    }
}
