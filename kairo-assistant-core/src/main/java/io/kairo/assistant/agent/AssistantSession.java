package io.kairo.assistant.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolRegistry;
import io.kairo.assistant.plugin.PluginManager;
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
        if (pluginManager != null) {
            pluginManager.loadPlugins();
        }
    }

    public void stop() {
        if (pluginManager != null) {
            pluginManager.unloadPlugins();
        }
        cronScheduler.stop();
        agent.interrupt();
    }
}
