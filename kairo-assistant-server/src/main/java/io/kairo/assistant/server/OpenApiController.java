package io.kairo.assistant.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpenApiController {

    @GetMapping(value = "/api/openapi.json", produces = "application/json")
    public Map<String, Object> openApiSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        spec.put("info", Map.of(
                "title", "Kairo Assistant API",
                "version", "0.1.0",
                "description", "REST API for Kairo AI Assistant — chat, tools, skills, channels, and MCP protocol"));
        spec.put("servers", List.of(Map.of("url", "/", "description", "Local server")));
        spec.put("paths", buildPaths());
        spec.put("components", buildComponents());
        return spec;
    }

    @GetMapping(value = "/api/docs", produces = "text/html")
    public String docsPage() {
        return """
                <!DOCTYPE html>
                <html><head>
                <title>Kairo Assistant API Docs</title>
                <meta charset="utf-8">
                <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
                </head><body>
                <div id="swagger-ui"></div>
                <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                <script>
                SwaggerUIBundle({ url: '/api/openapi.json', dom_id: '#swagger-ui',
                    deepLinking: true, presets: [SwaggerUIBundle.presets.apis] });
                </script>
                </body></html>
                """;
    }

    private Map<String, Object> buildPaths() {
        Map<String, Object> paths = new LinkedHashMap<>();

        paths.put("/api/health", Map.of("get", endpoint(
                "Health Check", "Returns service health and component status", "HealthResponse")));

        paths.put("/api/status", Map.of("get", endpoint(
                "Service Status", "Returns provider, model, and component counts", "StatusResponse")));

        paths.put("/api/tools", Map.of("get", endpoint(
                "List Tools", "Returns all registered tools with metadata", "ToolList")));

        paths.put("/api/tools/categories", Map.of("get", endpoint(
                "Tool Categories", "Returns tools grouped by category with counts", "ToolCategories")));

        paths.put("/api/tools/search", Map.of("get", Map.of(
                "summary", "Search Tools",
                "description", "Search tools by name or description keyword",
                "tags", List.of("capabilities"),
                "parameters", List.of(Map.of(
                        "name", "q", "in", "query", "required", true,
                        "schema", Map.of("type", "string"),
                        "description", "Search query")),
                "responses", Map.of("200", Map.of(
                        "description", "Search results",
                        "content", jsonContent("ToolSearchResult"))))));

        paths.put("/api/tools/{name}", Map.of("get", Map.of(
                "summary", "Tool Detail",
                "description", "Returns full detail of a single tool including input schema",
                "tags", List.of("capabilities"),
                "parameters", List.of(Map.of(
                        "name", "name", "in", "path", "required", true,
                        "schema", Map.of("type", "string"))),
                "responses", Map.of("200", Map.of(
                        "description", "Tool detail",
                        "content", jsonContent("ToolDetail"))))));

        paths.put("/api/skills", Map.of("get", endpoint(
                "List Skills", "Returns all available skills", "SkillList")));

        paths.put("/api/plugins", Map.of("get", endpoint(
                "List Plugins", "Returns all loaded plugins", "PluginList")));

        paths.put("/api/cron", Map.of(
                "get", endpoint("List Cron Tasks", "Returns all scheduled cron tasks", "CronTaskList"),
                "post", endpoint("Create Cron Task", "Create a new cron task", "CronTaskCreated")));
        paths.put("/api/cron/{taskId}", Map.of("delete", Map.of(
                "summary", "Delete Cron Task",
                "description", "Deletes a cron task by ID",
                "tags", List.of("scheduling"),
                "parameters", List.of(Map.of(
                        "name", "taskId", "in", "path", "required", true,
                        "schema", Map.of("type", "string"))),
                "responses", Map.of("200", Map.of(
                        "description", "Deletion result",
                        "content", jsonContent("CronDeleteResult"))))));

        paths.put("/api/memory", Map.of(
                "get", endpoint("List Memory", "Returns all memory entries in a scope", "MemoryList"),
                "post", endpoint("Save Memory", "Save a memory entry", "MemorySaved")));
        paths.put("/api/memory/search", Map.of("get", endpoint(
                "Search Memory", "Search memory entries by query", "MemorySearchResult")));
        paths.put("/api/memory/{id}", Map.of(
                "get", endpoint("Get Memory", "Get a memory entry by ID", "MemoryEntry"),
                "delete", Map.of(
                        "summary", "Delete Memory",
                        "description", "Deletes a memory entry by ID",
                        "tags", List.of("memory"),
                        "parameters", List.of(Map.of(
                                "name", "id", "in", "path", "required", true,
                                "schema", Map.of("type", "string"))),
                        "responses", Map.of("200", Map.of(
                                "description", "Deletion result",
                                "content", jsonContent("MemoryDeleteResult"))))));

        paths.put("/api/system", Map.of("get", endpoint(
                "System Info", "Returns JVM, OS, and memory information", "SystemInfo")));

        paths.put("/api/agent/state", Map.of("get", endpoint(
                "Agent State", "Returns current agent state, ID, and name", "AgentState")));

        paths.put("/api/config", Map.of("get", endpoint(
                "Configuration", "Returns sanitized configuration (API key masked)", "ConfigResponse")));

        paths.put("/api/health/detailed", Map.of("get", endpoint(
                "Detailed Health", "Returns detailed health with memory, runtime, and component info", "DetailedHealth")));

        paths.put("/api/channels", Map.of("get", endpoint(
                "List Channels", "Returns all messaging channels with status", "ChannelList")));

        paths.put("/api/analytics", Map.of("get", endpoint(
                "Analytics", "Returns usage analytics and statistics", "AnalyticsResponse")));

        paths.put("/api/analytics/endpoints", Map.of("get", endpoint(
                "Endpoint Analytics", "Returns per-endpoint request counts", "EndpointAnalytics")));

        paths.put("/api/analytics/tools", Map.of("get", endpoint(
                "Tool Analytics", "Returns per-tool call counts and statistics", "ToolAnalytics")));

        paths.put("/api/analytics/tokens", Map.of("get", endpoint(
                "Token Analytics", "Returns token usage breakdown with cost estimation", "TokenAnalytics")));

        paths.put("/api/analytics/latency", Map.of("get", endpoint(
                "Latency Analytics", "Returns agent call latency percentiles and averages", "LatencyAnalytics")));

        paths.put("/api/tools/history", Map.of("get", endpoint(
                "Tool History", "Returns recent tool call history", "ToolHistory")));

        paths.put("/api/sessions/search", Map.of("get", endpoint(
                "Search Sessions", "Searches across session content", "SessionSearchResult")));

        paths.put("/api/summarize", Map.of("post", endpoint(
                "Summarize Session", "Generates an AI summary of a session", "SummarizeResponse")));

        paths.put("/api/metrics", Map.of("get", Map.of(
                "summary", "Prometheus Metrics",
                "description", "Returns metrics in Prometheus text format",
                "tags", List.of("monitoring"),
                "responses", Map.of("200", Map.of(
                        "description", "Prometheus text",
                        "content", Map.of("text/plain", Map.of(
                                "schema", Map.of("type", "string"))))))));

        paths.put("/api/sessions", Map.of("get", endpoint(
                "List Sessions", "Returns all saved conversation sessions", "SessionList")));

        Map<String, Object> sessionIdParam = Map.of(
                "name", "sessionId", "in", "path", "required", true,
                "schema", Map.of("type", "string"));

        paths.put("/api/sessions/{sessionId}", Map.of(
                "get", Map.of(
                        "summary", "Get Session History",
                        "description", "Returns full message history for a session",
                        "tags", List.of("conversations"),
                        "parameters", List.of(sessionIdParam),
                        "responses", Map.of("200", Map.of(
                                "description", "Session messages",
                                "content", jsonContent("SessionHistory")))),
                "delete", Map.of(
                        "summary", "Delete Session",
                        "description", "Permanently deletes a conversation session",
                        "tags", List.of("conversations"),
                        "parameters", List.of(sessionIdParam),
                        "responses", Map.of("200", Map.of(
                                "description", "Deletion result",
                                "content", jsonContent("DeleteResult"))))));

        paths.put("/api/sessions/{sessionId}/export", Map.of("get", Map.of(
                "summary", "Export Session",
                "description", "Exports a session as markdown or JSON",
                "tags", List.of("conversations"),
                "parameters", List.of(sessionIdParam, Map.of(
                        "name", "format", "in", "query", "required", false,
                        "schema", Map.of("type", "string", "default", "markdown"))),
                "responses", Map.of("200", Map.of(
                        "description", "Exported content",
                        "content", jsonContent("ExportResult"))))));

        paths.put("/api/sessions/{sessionId}/title", Map.of("put", Map.of(
                "summary", "Rename Session",
                "description", "Sets a title for a conversation session",
                "tags", List.of("conversations"),
                "parameters", List.of(sessionIdParam),
                "requestBody", Map.of("required", true,
                        "content", jsonContent("RenameRequest")),
                "responses", Map.of("200", Map.of(
                        "description", "Rename result",
                        "content", jsonContent("RenameResult"))))));

        paths.put("/api/system-prompt", Map.of(
                "get", endpoint("Get System Prompt", "Returns custom instructions content", "SystemPromptResponse"),
                "put", Map.of(
                        "summary", "Update System Prompt",
                        "description", "Updates custom instructions (takes effect after restart)",
                        "tags", List.of("status"),
                        "requestBody", Map.of("required", true,
                                "content", jsonContent("SystemPromptUpdate")),
                        "responses", Map.of("200", Map.of(
                                "description", "Save result",
                                "content", jsonContent("SystemPromptSaved"))))));

        paths.put("/api/chat", Map.of("post", Map.of(
                "summary", "Send Chat Message",
                "description", "Sends a message and returns the agent response",
                "tags", List.of("chat"),
                "parameters", List.of(Map.of(
                        "name", "X-Session-Id", "in", "header", "required", false,
                        "schema", Map.of("type", "string"),
                        "description", "Optional session ID for conversation continuity")),
                "requestBody", Map.of("required", true,
                        "content", jsonContent("ChatRequest")),
                "responses", Map.of("200", Map.of(
                        "description", "Agent response",
                        "content", jsonContent("ChatResponse"))))));

        paths.put("/api/chat/stream", Map.of("post", Map.of(
                "summary", "Stream Chat Response",
                "description", "Sends a message and streams the response via SSE",
                "tags", List.of("chat"),
                "requestBody", Map.of("required", true,
                        "content", jsonContent("ChatRequest")),
                "responses", Map.of("200", Map.of(
                        "description", "Server-Sent Events stream",
                        "content", Map.of("text/event-stream", Map.of(
                                "schema", Map.of("type", "string"))))))));

        paths.put("/api/chat/interrupt", Map.of("post", Map.of(
                "summary", "Interrupt Agent",
                "description", "Interrupts the currently running agent call",
                "tags", List.of("chat"),
                "responses", Map.of("200", Map.of(
                        "description", "Interrupt result",
                        "content", jsonContent("InterruptResponse"))))));

        paths.put("/api/channels/dingtalk/webhook", Map.of("post", Map.of(
                "summary", "DingTalk Webhook",
                "description", "Receives inbound DingTalk messages",
                "tags", List.of("channels"),
                "requestBody", Map.of("required", true,
                        "content", jsonContent("DingTalkPayload")),
                "responses", Map.of("200", Map.of(
                        "description", "Ack",
                        "content", jsonContent("ChannelAck"))))));

        paths.put("/api/channels/feishu/webhook", Map.of("post", Map.of(
                "summary", "Feishu Webhook",
                "description", "Receives inbound Feishu (Lark) messages",
                "tags", List.of("channels"),
                "requestBody", Map.of("required", true,
                        "content", jsonContent("FeishuPayload")),
                "responses", Map.of("200", Map.of(
                        "description", "Ack",
                        "content", jsonContent("ChannelAck"))))));

        paths.put("/mcp", Map.of("post", Map.of(
                "summary", "MCP JSON-RPC",
                "description", "Model Context Protocol endpoint (JSON-RPC 2.0). Supports: initialize, tools/list, tools/call, ping",
                "tags", List.of("mcp"),
                "requestBody", Map.of("required", true,
                        "content", jsonContent("JsonRpcRequest")),
                "responses", Map.of("200", Map.of(
                        "description", "JSON-RPC response",
                        "content", jsonContent("JsonRpcResponse"))))));

        paths.put("/api/sse/connect", Map.of("get", Map.of(
                "summary", "SSE Connect",
                "description", "Open persistent SSE connection. Alternative to WebSocket.",
                "tags", List.of("sse"),
                "parameters", List.of(Map.of(
                        "name", "clientId", "in", "query", "required", false,
                        "schema", Map.of("type", "string", "default", "default"))),
                "responses", Map.of("200", Map.of(
                        "description", "SSE event stream",
                        "content", Map.of("text/event-stream", Map.of(
                                "schema", Map.of("type", "string"))))))));

        paths.put("/api/sse/disconnect", Map.of("post", Map.of(
                "summary", "SSE Disconnect",
                "description", "Disconnect an SSE client by ID",
                "tags", List.of("sse"),
                "parameters", List.of(Map.of(
                        "name", "clientId", "in", "query", "required", false,
                        "schema", Map.of("type", "string"))),
                "responses", Map.of("200", Map.of(
                        "description", "Disconnect result",
                        "content", jsonContent("InterruptResponse"))))));

        paths.put("/api/sse/connections", Map.of("get", endpoint(
                "SSE Connections", "List active SSE connections", "SseConnections")));

        paths.put("/api/sse/send", Map.of("post", Map.of(
                "summary", "SSE Send Message",
                "description", "Send message through an active SSE connection",
                "tags", List.of("sse"),
                "parameters", List.of(Map.of(
                        "name", "clientId", "in", "query", "required", false,
                        "schema", Map.of("type", "string"))),
                "requestBody", Map.of("required", true,
                        "content", jsonContent("ChatRequest")),
                "responses", Map.of("200", Map.of(
                        "description", "Processing status",
                        "content", jsonContent("InterruptResponse"))))));

        paths.put("/api/tools/execute", Map.of("post", Map.of(
                "summary", "Execute Tool",
                "description", "Execute a tool directly, bypassing the agent. Returns tool output.",
                "tags", List.of("tools"),
                "requestBody", Map.of("required", true,
                        "content", jsonContent("ToolExecuteRequest")),
                "responses", Map.of("200", Map.of(
                        "description", "Tool execution result",
                        "content", jsonContent("ToolExecuteResponse"))))));

        paths.put("/api/context", Map.of(
                "get", endpoint("Get Context",
                        "Returns the agent conversation history with truncated previews", "ContextResponse"),
                "delete", Map.of(
                        "summary", "Clear Context",
                        "description", "Clears the agent conversation history",
                        "tags", List.of("status"),
                        "responses", Map.of("200", Map.of(
                                "description", "Clear result",
                                "content", jsonContent("ContextClearResult"))))));

        Map<String, Object> convSessionIdParam = Map.of(
                "name", "sessionId", "in", "path", "required", true,
                "schema", Map.of("type", "string"),
                "description", "Conversation session ID");

        paths.put("/api/conversations", Map.of("get", endpoint(
                "List Conversations", "Returns all saved conversations with previews and titles", "ConversationList")));

        paths.put("/api/conversations/search", Map.of("get", Map.of(
                "summary", "Search Conversations",
                "description", "Searches across all conversation messages for matching text",
                "tags", List.of("conversations"),
                "parameters", List.of(Map.of(
                        "name", "q", "in", "query", "required", true,
                        "schema", Map.of("type", "string"),
                        "description", "Search query")),
                "responses", Map.of("200", Map.of(
                        "description", "Search results",
                        "content", jsonContent("ConversationSearchResult"))))));

        paths.put("/api/conversations/{sessionId}", Map.of(
                "get", Map.of(
                        "summary", "Get Conversation",
                        "description", "Returns full message history with title and count",
                        "tags", List.of("conversations"),
                        "parameters", List.of(convSessionIdParam),
                        "responses", Map.of("200", Map.of(
                                "description", "Conversation detail",
                                "content", jsonContent("ConversationDetail")))),
                "delete", Map.of(
                        "summary", "Delete Conversation",
                        "description", "Permanently deletes a conversation",
                        "tags", List.of("conversations"),
                        "parameters", List.of(convSessionIdParam),
                        "responses", Map.of("200", Map.of(
                                "description", "Deletion result",
                                "content", jsonContent("DeleteResult"))))));

        paths.put("/api/conversations/{sessionId}/export", Map.of("get", Map.of(
                "summary", "Export Conversation",
                "description", "Exports a conversation as markdown or JSON",
                "tags", List.of("conversations"),
                "parameters", List.of(convSessionIdParam, Map.of(
                        "name", "format", "in", "query", "required", false,
                        "schema", Map.of("type", "string", "default", "markdown"))),
                "responses", Map.of("200", Map.of(
                        "description", "Exported content",
                        "content", Map.of("text/plain", Map.of(
                                "schema", Map.of("type", "string"))))))));

        paths.put("/api/conversations/{sessionId}/title", Map.of("put", Map.of(
                "summary", "Set Conversation Title",
                "description", "Sets or updates a conversation title",
                "tags", List.of("conversations"),
                "parameters", List.of(convSessionIdParam),
                "requestBody", Map.of("required", true,
                        "content", jsonContent("RenameRequest")),
                "responses", Map.of("200", Map.of(
                        "description", "Update result",
                        "content", jsonContent("RenameResult"))))));

        paths.put("/api/openapi.json", Map.of("get", endpoint(
                "OpenAPI Spec", "Returns this OpenAPI specification", "object")));

        paths.put("/api/docs", Map.of("get", Map.of(
                "summary", "API Documentation",
                "description", "Swagger UI for exploring the API",
                "tags", List.of("docs"),
                "responses", Map.of("200", Map.of(
                        "description", "HTML page",
                        "content", Map.of("text/html", Map.of(
                                "schema", Map.of("type", "string"))))))));

        return paths;
    }

    private Map<String, Object> buildComponents() {
        Map<String, Object> schemas = new LinkedHashMap<>();

        schemas.put("ChatRequest", objectSchema(Map.of(
                "message", prop("string", "The user message to send"))));

        schemas.put("ChatResponse", objectSchema(Map.of(
                "response", prop("string", "Agent response text"),
                "role", prop("string", "Response role (ASSISTANT)"),
                "sessionId", prop("string", "Session ID for continuity"))));

        schemas.put("InterruptResponse", objectSchema(Map.of(
                "status", prop("string", "Interrupt status"))));

        schemas.put("HealthResponse", objectSchema(Map.of(
                "status", prop("string", "Health status"),
                "uptime", prop("integer", "Uptime in seconds"),
                "version", prop("string", "Version string"),
                "components", prop("object", "Component health map"))));

        schemas.put("StatusResponse", objectSchema(Map.of(
                "status", prop("string", "Running status"),
                "provider", prop("string", "Model provider"),
                "model", prop("string", "Model name"),
                "toolCount", prop("integer", "Number of tools"),
                "skillCount", prop("integer", "Number of skills"),
                "pluginCount", prop("integer", "Number of plugins"))));

        schemas.put("ToolList", Map.of(
                "type", "array",
                "items", objectSchema(Map.of(
                        "name", prop("string", "Tool name"),
                        "description", prop("string", "Tool description"),
                        "category", prop("string", "Tool category"),
                        "sideEffect", prop("string", "Side effect level")))));

        schemas.put("ToolSearchResult", objectSchema(Map.of(
                "query", prop("string", "Search query"),
                "count", prop("integer", "Number of matching tools"),
                "tools", prop("array", "Matching tools with name, description, category"))));

        schemas.put("ToolCategories", objectSchema(Map.of(
                "GENERAL", prop("object", "Category with count and tool list"),
                "INFORMATION", prop("object", "Category with count and tool list"),
                "FILE_AND_CODE", prop("object", "Category with count and tool list"),
                "AGENT_AND_TASK", prop("object", "Category with count and tool list"),
                "EXTERNAL", prop("object", "Category with count and tool list"))));

        schemas.put("ToolDetail", objectSchema(Map.of(
                "name", prop("string", "Tool name"),
                "description", prop("string", "Tool description"),
                "category", prop("string", "Tool category"),
                "inputSchema", prop("object", "JSON Schema of tool input parameters"),
                "error", prop("string", "Error message if tool not found"))));

        schemas.put("SkillList", Map.of(
                "type", "array",
                "items", objectSchema(Map.of(
                        "name", prop("string", "Skill name"),
                        "version", prop("string", "Skill version"),
                        "description", prop("string", "Skill description"),
                        "category", prop("string", "Skill category")))));

        schemas.put("SessionList", Map.of(
                "type", "array",
                "items", objectSchema(Map.of(
                        "id", prop("string", "Session ID"),
                        "preview", prop("string", "Content preview"),
                        "lastModified", prop("string", "Last modified timestamp")))));

        schemas.put("SessionHistory", Map.of(
                "type", "array",
                "items", objectSchema(Map.of(
                        "type", prop("string", "Entry type"),
                        "role", prop("string", "Message role"),
                        "content", prop("string", "Message content"),
                        "timestamp", prop("string", "Message timestamp")))));

        schemas.put("SystemInfo", objectSchema(Map.of(
                "javaVersion", prop("string", "Java version"),
                "os", prop("string", "Operating system"),
                "processors", prop("integer", "Available processors"),
                "memoryUsedMB", prop("integer", "Used memory in MB"),
                "memoryMaxMB", prop("integer", "Max memory in MB"),
                "activeThreads", prop("integer", "Active thread count"))));

        schemas.put("AgentState", objectSchema(Map.of(
                "state", prop("string", "Agent state (IDLE, THINKING, ACTING)"),
                "agentId", prop("string", "Agent ID"),
                "agentName", prop("string", "Agent display name"))));

        schemas.put("ConfigResponse", objectSchema(Map.of(
                "modelProvider", prop("string", "Model provider name"),
                "modelName", prop("string", "Model name"),
                "apiKey", prop("string", "Masked API key"),
                "maxIterations", prop("integer", "Max iterations per request"),
                "timeout", prop("string", "Request timeout"),
                "tokenBudget", prop("integer", "Token budget"))));

        schemas.put("DetailedHealth", objectSchema(Map.of(
                "status", prop("string", "Overall health status"),
                "memory", prop("object", "Memory usage details"),
                "runtime", prop("object", "Runtime information"),
                "components", prop("object", "Component health details"))));

        schemas.put("ChannelList", objectSchema(Map.of(
                "total", prop("integer", "Total channels"),
                "active", prop("integer", "Active channels"),
                "channels", prop("array", "Channel details"))));

        schemas.put("AnalyticsResponse", objectSchema(Map.of(
                "uptime", prop("integer", "Uptime in seconds"),
                "totalSessions", prop("integer", "Total sessions"),
                "registeredTools", prop("integer", "Registered tools"),
                "registeredSkills", prop("integer", "Registered skills"),
                "provider", prop("string", "Model provider"),
                "model", prop("string", "Model name"))));

        schemas.put("ToolHistory", objectSchema(Map.of(
                "totalCalls", prop("integer", "Total tool calls"),
                "totalErrors", prop("integer", "Total errors"),
                "calls", prop("array", "Recent call records"))));

        schemas.put("SessionSearchResult", objectSchema(Map.of(
                "query", prop("string", "Search query"),
                "total", prop("integer", "Total results"),
                "results", prop("array", "Matching sessions"))));

        schemas.put("SummarizeResponse", objectSchema(Map.of(
                "sessionId", prop("string", "Session ID"),
                "summary", prop("string", "AI-generated summary"),
                "messageCount", prop("integer", "Message count"))));

        schemas.put("DeleteResult", objectSchema(Map.of(
                "status", prop("string", "Deletion status"),
                "sessionId", prop("string", "Session ID"))));

        schemas.put("ExportResult", objectSchema(Map.of(
                "sessionId", prop("string", "Session ID"),
                "format", prop("string", "Export format"),
                "content", prop("string", "Exported content"))));

        schemas.put("RenameRequest", objectSchema(Map.of(
                "title", prop("string", "New session title"))));

        schemas.put("RenameResult", objectSchema(Map.of(
                "status", prop("string", "Result status"),
                "sessionId", prop("string", "Session ID"),
                "title", prop("string", "New title"))));

        schemas.put("SystemPromptResponse", objectSchema(Map.of(
                "content", prop("string", "Custom instructions content"),
                "path", prop("string", "File path"),
                "note", prop("string", "Usage note"))));

        schemas.put("SystemPromptUpdate", objectSchema(Map.of(
                "content", prop("string", "New custom instructions content"))));

        schemas.put("SystemPromptSaved", objectSchema(Map.of(
                "status", prop("string", "Save status"),
                "note", prop("string", "Usage note"))));

        schemas.put("EndpointAnalytics", objectSchema(Map.of(
                "endpoints", prop("object", "Map of endpoint to request count"))));

        schemas.put("ToolAnalytics", objectSchema(Map.of(
                "totalToolCalls", prop("integer", "Total tool invocations across all tools"),
                "uniqueToolsUsed", prop("integer", "Number of distinct tools called"),
                "tools", prop("object", "Map of tool name to call count"))));

        schemas.put("TokenAnalytics", objectSchema(Map.of(
                "inputTokens", prop("integer", "Total input tokens consumed"),
                "outputTokens", prop("integer", "Total output tokens generated"),
                "totalTokens", prop("integer", "Total tokens (input + output)"),
                "totalMessages", prop("integer", "Total messages processed"),
                "avgTokensPerMessage", prop("integer", "Average tokens per message"),
                "estimatedCostUsd", prop("number", "Estimated cost in USD"),
                "pricing", prop("object", "Pricing model details"))));

        schemas.put("LatencyAnalytics", objectSchema(Map.of(
                "percentiles", prop("object", "p50/p90/p99 latency in ms"),
                "totalAgentCalls", prop("integer", "Total agent calls"),
                "totalDurationMs", prop("integer", "Total duration in ms"),
                "avgDurationMs", prop("integer", "Average call duration in ms"))));

        schemas.put("ToolExecuteRequest", objectSchema(Map.of(
                "tool", prop("string", "Tool name to execute"),
                "args", prop("object", "Tool arguments as key-value pairs"))));

        schemas.put("ToolExecuteResponse", objectSchema(Map.of(
                "tool", prop("string", "Executed tool name"),
                "success", prop("boolean", "Whether the tool succeeded"),
                "content", prop("string", "Tool output content"))));

        schemas.put("ContextResponse", objectSchema(Map.of(
                "state", prop("string", "Agent state"),
                "messageCount", prop("integer", "Number of messages in context"),
                "totalTokens", prop("integer", "Total tokens used"),
                "messages", prop("array", "Message previews with role and truncated text"))));

        schemas.put("ContextClearResult", objectSchema(Map.of(
                "status", prop("string", "Clear result (cleared or error)"))));

        schemas.put("SseConnections", objectSchema(Map.of(
                "count", prop("integer", "Number of active SSE connections"),
                "clientIds", prop("array", "List of connected client IDs"))));

        schemas.put("ConversationList", objectSchema(Map.of(
                "total", prop("integer", "Total conversations"),
                "conversations", prop("array", "List of conversation summaries with id, preview, title, lastModified"))));

        schemas.put("ConversationDetail", objectSchema(Map.of(
                "sessionId", prop("string", "Conversation session ID"),
                "title", prop("string", "Conversation title (if set)"),
                "messageCount", prop("integer", "Number of messages"),
                "messages", prop("array", "List of messages with role, content, and timestamp"))));

        schemas.put("ConversationSearchResult", objectSchema(Map.of(
                "query", prop("string", "Search query"),
                "total", prop("integer", "Total matching messages"),
                "results", prop("array", "Matching messages with sessionId, role, content, timestamp"))));

        schemas.put("ChannelAck", objectSchema(Map.of(
                "success", prop("boolean", "Whether the message was processed"))));

        schemas.put("DingTalkPayload", objectSchema(Map.of(
                "text", prop("object", "Message text object"),
                "senderId", prop("string", "Sender ID"),
                "senderNick", prop("string", "Sender nickname"))));

        schemas.put("FeishuPayload", objectSchema(Map.of(
                "event", prop("object", "Feishu event payload"))));

        schemas.put("JsonRpcRequest", objectSchema(Map.of(
                "jsonrpc", prop("string", "JSON-RPC version (2.0)"),
                "id", prop("integer", "Request ID"),
                "method", prop("string", "Method name"),
                "params", prop("object", "Method parameters"))));

        schemas.put("JsonRpcResponse", objectSchema(Map.of(
                "jsonrpc", prop("string", "JSON-RPC version"),
                "id", prop("integer", "Request ID"),
                "result", prop("object", "Result payload"),
                "error", prop("object", "Error object (if failed)"))));

        return Map.of("schemas", schemas);
    }

    private Map<String, Object> endpoint(String summary, String description, String schemaRef) {
        return Map.of(
                "summary", summary,
                "description", description,
                "tags", List.of(inferTag(summary)),
                "responses", Map.of("200", Map.of(
                        "description", "Success",
                        "content", jsonContent(schemaRef))));
    }

    private String inferTag(String summary) {
        String lower = summary.toLowerCase();
        if (lower.contains("health") || lower.contains("metric")) return "monitoring";
        if (lower.contains("session")) return "conversations";
        if (lower.contains("tool") || lower.contains("skill")) return "capabilities";
        return "status";
    }

    private Map<String, Object> jsonContent(String schemaRef) {
        return Map.of("application/json", Map.of(
                "schema", Map.of("$ref", "#/components/schemas/" + schemaRef)));
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties);
    }

    private Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }
}
