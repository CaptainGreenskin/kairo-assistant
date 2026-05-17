package io.kairo.assistant.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisionToolTest {

    private VisionTool tool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tool = new VisionTool();
    }

    @Test
    void requiresFilePath() {
        ToolResult result = tool.execute(Map.of(), ctx()).block();
        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.content().contains("file_path"));
    }

    @Test
    void failsOnNonExistentFile() {
        ToolResult result = tool.execute(
                Map.of("file_path", "/nonexistent/image.png"), ctx()).block();
        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void rejectsUnsupportedFormat() throws IOException {
        Path txt = tempDir.resolve("doc.txt");
        Files.writeString(txt, "not an image");
        ToolResult result = tool.execute(
                Map.of("file_path", txt.toString()), ctx()).block();
        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Unsupported"));
    }

    @Test
    void loadsValidPng() throws IOException {
        Path png = tempDir.resolve("test.png");
        byte[] header = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        Files.write(png, header);

        ToolResult result = tool.execute(
                Map.of("file_path", png.toString(), "prompt", "What is this?"), ctx()).block();
        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().contains("test.png"));
        assertTrue(result.content().contains("image/png"));
    }

    @Test
    void schemaHasRequiredField() {
        var schema = tool.inputSchema();
        assertTrue(schema.required().contains("file_path"));
    }

    private ToolContext ctx() {
        return new ToolContext("test", "s1", Map.of());
    }
}
