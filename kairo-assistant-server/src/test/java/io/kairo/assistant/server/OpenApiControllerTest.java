package io.kairo.assistant.server;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiControllerTest {

    private final OpenApiController controller = new OpenApiController();

    @Test
    void specContainsRequiredFields() {
        Map<String, Object> spec = controller.openApiSpec();

        assertEquals("3.0.3", spec.get("openapi"));
        assertNotNull(spec.get("info"));
        assertNotNull(spec.get("paths"));
        assertNotNull(spec.get("components"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specInfoHasTitleAndVersion() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> info = (Map<String, Object>) spec.get("info");

        assertEquals("Kairo Assistant API", info.get("title"));
        assertEquals("0.1.0", info.get("version"));
        assertNotNull(info.get("description"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsChatEndpoints() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/api/chat"));
        assertTrue(paths.containsKey("/api/chat/stream"));
        assertTrue(paths.containsKey("/api/chat/interrupt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsMonitoringEndpoints() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/api/health"));
        assertTrue(paths.containsKey("/api/status"));
        assertTrue(paths.containsKey("/api/metrics"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsMcpEndpoint() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/mcp"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsSseEndpoints() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/api/sse/connect"));
        assertTrue(paths.containsKey("/api/sse/send"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsChannelEndpoints() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/api/channels/dingtalk/webhook"));
        assertTrue(paths.containsKey("/api/channels/feishu/webhook"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specComponentsHaveSchemas() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        assertTrue(schemas.containsKey("ChatRequest"));
        assertTrue(schemas.containsKey("ChatResponse"));
        assertTrue(schemas.containsKey("HealthResponse"));
        assertTrue(schemas.containsKey("StatusResponse"));
        assertTrue(schemas.containsKey("ToolList"));
        assertTrue(schemas.containsKey("SkillList"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specServersListIsPresent() {
        Map<String, Object> spec = controller.openApiSpec();
        List<Object> servers = (List<Object>) spec.get("servers");

        assertNotNull(servers);
        assertFalse(servers.isEmpty());
    }

    @Test
    void docsPageReturnsHtml() {
        String html = controller.docsPage();

        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("swagger-ui"));
        assertTrue(html.contains("/api/openapi.json"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsDocsSelfReference() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/api/openapi.json"));
        assertTrue(paths.containsKey("/api/docs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsNewEndpoints() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/api/system"), "Missing /api/system");
        assertTrue(paths.containsKey("/api/agent/state"), "Missing /api/agent/state");
        assertTrue(paths.containsKey("/api/config"), "Missing /api/config");
        assertTrue(paths.containsKey("/api/health/detailed"), "Missing /api/health/detailed");
        assertTrue(paths.containsKey("/api/channels"), "Missing /api/channels");
        assertTrue(paths.containsKey("/api/analytics"), "Missing /api/analytics");
        assertTrue(paths.containsKey("/api/tools/history"), "Missing /api/tools/history");
        assertTrue(paths.containsKey("/api/sessions/search"), "Missing /api/sessions/search");
        assertTrue(paths.containsKey("/api/summarize"), "Missing /api/summarize");
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsNewSchemas() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        assertTrue(schemas.containsKey("SystemInfo"));
        assertTrue(schemas.containsKey("AgentState"));
        assertTrue(schemas.containsKey("ConfigResponse"));
        assertTrue(schemas.containsKey("DetailedHealth"));
        assertTrue(schemas.containsKey("ChannelList"));
        assertTrue(schemas.containsKey("AnalyticsResponse"));
        assertTrue(schemas.containsKey("ToolHistory"));
        assertTrue(schemas.containsKey("SessionSearchResult"));
        assertTrue(schemas.containsKey("SummarizeResponse"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsCronAndMemoryEndpoints() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/api/cron"));
        assertTrue(paths.containsKey("/api/cron/{taskId}"));
        assertTrue(paths.containsKey("/api/memory"));
        assertTrue(paths.containsKey("/api/memory/search"));
        assertTrue(paths.containsKey("/api/memory/{id}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsSessionManagementEndpoints() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/api/sessions/{sessionId}/export"), "Missing session export");
        assertTrue(paths.containsKey("/api/sessions/{sessionId}/title"), "Missing session rename");
        assertTrue(paths.containsKey("/api/system-prompt"), "Missing system prompt");

        Map<String, Object> sessionPath = (Map<String, Object>) paths.get("/api/sessions/{sessionId}");
        assertTrue(sessionPath.containsKey("delete"), "Missing DELETE on sessions/{sessionId}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsAnalyticsEndpointsPath() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertTrue(paths.containsKey("/api/analytics/endpoints"), "Missing /api/analytics/endpoints");
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsToolDetailEndpoint() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertTrue(paths.containsKey("/api/tools/{name}"), "Missing /api/tools/{name}");

        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertTrue(schemas.containsKey("ToolDetail"), "Missing ToolDetail schema");
    }

    @Test
    @SuppressWarnings("unchecked")
    void specContainsSessionManagementSchemas() {
        Map<String, Object> spec = controller.openApiSpec();
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        assertTrue(schemas.containsKey("DeleteResult"));
        assertTrue(schemas.containsKey("ExportResult"));
        assertTrue(schemas.containsKey("RenameRequest"));
        assertTrue(schemas.containsKey("RenameResult"));
        assertTrue(schemas.containsKey("SystemPromptResponse"));
        assertTrue(schemas.containsKey("SystemPromptUpdate"));
        assertTrue(schemas.containsKey("SystemPromptSaved"));
    }
}
