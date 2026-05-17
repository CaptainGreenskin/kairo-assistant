package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolInvocation;
import io.kairo.api.tool.ToolResult;
import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.memory.InMemoryStore;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class McpServerControllerTest {

    private McpServerController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new ToolDefinition(
                "test_tool", "A test tool", ToolCategory.GENERAL,
                new JsonSchema("object", Map.of("query",
                        new JsonSchema("string", null, null, "Search query")),
                        List.of("query"), "Test input"),
                null));

        var config = AssistantConfig.builder().apiKey("test").build();
        var skillRegistry = AssistantSkills.createRegistry();
        var session = new AssistantSession(
                new TestFixtures.StubAgent(), toolRegistry, new McpToolExecutor(),
                new InMemoryStore(), new TestFixtures.StubCronScheduler(),
                skillRegistry,
                new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp")),
                config);

        controller = new McpServerController(session);
    }

    @Test
    void initializeReturnsServerInfo() {
        var request = Map.<String, Object>of("jsonrpc", "2.0", "id", 1, "method", "initialize");
        var result = controller.handle(request).block();

        assertNotNull(result);
        assertEquals("2.0", result.get("jsonrpc"));
        assertEquals(1, result.get("id"));

        @SuppressWarnings("unchecked")
        var inner = (Map<String, Object>) result.get("result");
        assertNotNull(inner);
        assertEquals("2024-11-05", inner.get("protocolVersion"));

        @SuppressWarnings("unchecked")
        var serverInfo = (Map<String, Object>) inner.get("serverInfo");
        assertEquals("kairo-assistant", serverInfo.get("name"));
    }

    @Test
    void pingReturnsEmptyResult() {
        var request = Map.<String, Object>of("jsonrpc", "2.0", "id", 2, "method", "ping");
        var result = controller.handle(request).block();

        assertNotNull(result);
        assertEquals(2, result.get("id"));
        assertNotNull(result.get("result"));
        assertNull(result.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsListReturnsRegisteredTools() {
        var request = Map.<String, Object>of("jsonrpc", "2.0", "id", 3, "method", "tools/list");
        var result = controller.handle(request).block();

        assertNotNull(result);
        var inner = (Map<String, Object>) result.get("result");
        var tools = (List<Map<String, Object>>) inner.get("tools");
        assertFalse(tools.isEmpty());

        var tool = tools.stream()
                .filter(t -> "test_tool".equals(t.get("name")))
                .findFirst();
        assertTrue(tool.isPresent());
        assertEquals("A test tool", tool.get().get("description"));
    }

    @Test
    void invalidJsonRpcVersionReturnsError() {
        var request = Map.<String, Object>of("jsonrpc", "1.0", "id", 4, "method", "initialize");
        var result = controller.handle(request).block();

        assertNotNull(result);
        assertNotNull(result.get("error"));
        assertNull(result.get("result"));
    }

    @Test
    void missingMethodReturnsError() {
        var request = Map.<String, Object>of("jsonrpc", "2.0", "id", 5);
        var result = controller.handle(request).block();

        assertNotNull(result);
        assertNotNull(result.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownMethodReturnsError() {
        var request = Map.<String, Object>of("jsonrpc", "2.0", "id", 6, "method", "unknown/method");
        var result = controller.handle(request).block();

        assertNotNull(result);
        var error = (Map<String, Object>) result.get("error");
        assertNotNull(error);
        assertEquals(-32601, error.get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsCallMissingParamsReturnsError() {
        var request = Map.<String, Object>of("jsonrpc", "2.0", "id", 7, "method", "tools/call");
        var result = controller.handle(request).block();

        assertNotNull(result);
        var error = (Map<String, Object>) result.get("error");
        assertNotNull(error);
        assertEquals(-32602, error.get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsCallMissingToolNameReturnsError() {
        var request = Map.of("jsonrpc", (Object) "2.0", "id", (Object) 8,
                "method", (Object) "tools/call",
                "params", (Object) Map.of("arguments", Map.of()));
        var result = controller.handle(request).block();

        assertNotNull(result);
        var error = (Map<String, Object>) result.get("error");
        assertNotNull(error);
        assertEquals(-32602, error.get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsCallWithNonMapParamsReturnsError() {
        var request = Map.of("jsonrpc", (Object) "2.0", "id", (Object) 9,
                "method", (Object) "tools/call",
                "params", (Object) "not-a-map");
        var result = controller.handle(request).block();

        assertNotNull(result);
        var error = (Map<String, Object>) result.get("error");
        assertNotNull(error);
        assertEquals(-32602, error.get("code"));
        assertTrue(error.get("message").toString().contains("params must be an object"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsCallWithNonStringNameReturnsError() {
        var request = Map.of("jsonrpc", (Object) "2.0", "id", (Object) 10,
                "method", (Object) "tools/call",
                "params", (Object) Map.of("name", (Object) 123));
        var result = controller.handle(request).block();

        assertNotNull(result);
        var error = (Map<String, Object>) result.get("error");
        assertNotNull(error);
        assertEquals(-32602, error.get("code"));
        assertTrue(error.get("message").toString().contains("tool name must be a string"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsCallWithNonMapArgumentsReturnsError() {
        var request = Map.of("jsonrpc", (Object) "2.0", "id", (Object) 11,
                "method", (Object) "tools/call",
                "params", (Object) Map.of("name", (Object) "test_tool", "arguments", (Object) "bad"));
        var result = controller.handle(request).block();

        assertNotNull(result);
        var error = (Map<String, Object>) result.get("error");
        assertNotNull(error);
        assertEquals(-32602, error.get("code"));
        assertTrue(error.get("message").toString().contains("arguments must be an object"));
    }

    private static class McpToolExecutor implements ToolExecutor {
        @Override public Mono<ToolResult> execute(String name, Map<String, Object> input) {
            return Mono.just(ToolResult.success("tool-1", "result for " + name));
        }
        @Override public Mono<ToolResult> execute(String name, Map<String, Object> input, Duration t) {
            return execute(name, input);
        }
        @Override public Flux<ToolResult> executeParallel(List<ToolInvocation> inv) { return Flux.empty(); }
    }
}
