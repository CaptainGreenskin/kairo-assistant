package io.kairo.assistant.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.plugin.PluginManager;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolRegistry;
import io.kairo.core.cron.CronScheduler;

public record AssistantSession(
        Agent agent,
        ToolRegistry toolRegistry,
        ToolExecutor toolExecutor,
        MemoryStore memoryStore,
        CronScheduler cronScheduler,
        SkillRegistry skillRegistry,
        PluginManager pluginManager,
        AssistantConfig config) {

    public java.util.Map<String, Object> dependencies() {
        java.util.Map<String, Object> deps = new java.util.LinkedHashMap<>();
        if (memoryStore != null) deps.put("memoryStore", memoryStore);
        if (cronScheduler != null) deps.put("cronScheduler", cronScheduler);
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
    }
}
