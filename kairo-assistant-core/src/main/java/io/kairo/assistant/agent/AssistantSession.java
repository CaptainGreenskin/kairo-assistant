package io.kairo.assistant.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.SubagentRegistry;
import io.kairo.api.mcp.McpPlugin;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.plugin.PluginManager;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolRegistry;
import io.kairo.core.cron.CronScheduler;
import io.kairo.plugin.hook.HookExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record AssistantSession(
        Agent agent,
        ToolRegistry toolRegistry,
        ToolExecutor toolExecutor,
        MemoryStore memoryStore,
        CronScheduler cronScheduler,
        SkillRegistry skillRegistry,
        PluginManager pluginManager,
        SubagentRegistry subagentRegistry,
        McpPlugin pluginMcp,
        HookExecutor pluginHookExecutor,
        PluginHookCatalog pluginHookCatalog,
        AssistantConfig config) {

    private static final Logger log = LoggerFactory.getLogger(AssistantSession.class);

    /**
     * Backwards-compatible constructor — keeps the original 8-arg shape working while we migrate
     * call-sites. Wires the plugin runtime extras to nulls/no-ops so legacy fixtures continue to
     * compile without changes.
     */
    public AssistantSession(
            Agent agent,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            MemoryStore memoryStore,
            CronScheduler cronScheduler,
            SkillRegistry skillRegistry,
            PluginManager pluginManager,
            AssistantConfig config) {
        this(
                agent,
                toolRegistry,
                toolExecutor,
                memoryStore,
                cronScheduler,
                skillRegistry,
                pluginManager,
                null,
                null,
                null,
                null,
                config);
    }

    public java.util.Map<String, Object> dependencies() {
        java.util.Map<String, Object> deps = new java.util.LinkedHashMap<>();
        if (memoryStore != null) deps.put("memoryStore", memoryStore);
        if (cronScheduler != null) deps.put("cronScheduler", cronScheduler);
        if (subagentRegistry != null) deps.put("subagentRegistry", subagentRegistry);
        if (pluginHookCatalog != null) deps.put("pluginHookCatalog", pluginHookCatalog);
        return deps;
    }

    public void start() {
        cronScheduler.start();
        // PluginManager has no global "load all plugins" action — plugins are managed
        // individually via install/enable/disable. The lifecycle integration point shifts
        // from session start/stop to the install pipeline (see ADR-029).
    }

    public void stop() {
        cronScheduler.stop();
        agent.interrupt();
        // Tear down plugin runtime extras in reverse-init order so subscriptions die before the
        // sources they observe.
        if (pluginHookCatalog != null) {
            try {
                pluginHookCatalog.close();
            } catch (Exception e) {
                log.warn("PluginHookCatalog close failed: {}", e.getMessage());
            }
        }
        if (pluginMcp != null) {
            try {
                pluginMcp.close();
            } catch (Exception e) {
                log.warn("Plugin MCP shutdown failed: {}", e.getMessage());
            }
        }
    }
}
