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

    @Test
    void rejectsBlankFilePath() {
        ToolResult result = tool.execute(Map.of("file_path", "  "), ctx()).block();
        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.content().contains("file_path"));
    }

    @Test
    void rejectsDirectory() throws IOException {
        ToolResult result = tool.execute(
                Map.of("file_path", tempDir.toString()), ctx()).block();
        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Not a regular file"));
    }

    @Test
    void loadsValidJpeg() throws IOException {
        Path jpg = tempDir.resolve("photo.jpg");
        Files.write(jpg, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});

        ToolResult result = tool.execute(
                Map.of("file_path", jpg.toString()), ctx()).block();
        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().contains("image/jpeg"));
        assertTrue(result.content().contains("photo.jpg"));
    }

    @Test
    void loadsGif() throws IOException {
        Path gif = tempDir.resolve("anim.gif");
        Files.write(gif, new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61});

        ToolResult result = tool.execute(
                Map.of("file_path", gif.toString()), ctx()).block();
        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().contains("image/gif"));
    }

    @Test
    void loadsWebp() throws IOException {
        Path webp = tempDir.resolve("img.webp");
        Files.write(webp, new byte[]{0x52, 0x49, 0x46, 0x46});

        ToolResult result = tool.execute(
                Map.of("file_path", webp.toString()), ctx()).block();
        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().contains("image/webp"));
    }

    @Test
    void defaultPromptUsed() throws IOException {
        Path png = tempDir.resolve("def.png");
        Files.write(png, new byte[]{(byte) 0x89, 0x50});

        ToolResult result = tool.execute(
                Map.of("file_path", png.toString()), ctx()).block();
        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().contains("Describe this image"));
    }

    @Test
    void toolAnnotation() {
        var ann = VisionTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertNotNull(ann);
        assertEquals("vision", ann.name());
        assertEquals(io.kairo.api.tool.ToolSideEffect.READ_ONLY, ann.sideEffect());
    }

    @Test
    void schemaHasPromptField() {
        var schema = tool.inputSchema();
        assertTrue(schema.properties().containsKey("prompt"));
        assertEquals("object", schema.type());
    }

    private ToolContext ctx() {
        return new ToolContext("test", "s1", Map.of());
    }
}
