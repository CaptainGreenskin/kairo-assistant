package io.kairo.assistant.plugin;

import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final List<AssistantPlugin> plugins = new ArrayList<>();
    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final Path dataDir;

    public PluginManager(ToolRegistry toolRegistry, SkillRegistry skillRegistry, Path dataDir) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.dataDir = dataDir;
    }

    public void loadPlugins() {
        ServiceLoader<AssistantPlugin> loader = ServiceLoader.load(AssistantPlugin.class);
        AssistantPlugin.PluginContext context = new DefaultPluginContext();

        for (AssistantPlugin plugin : loader) {
            try {
                plugin.onLoad(context);
                plugins.add(plugin);
                log.info("Loaded plugin: {} v{}", plugin.name(), plugin.version());
            } catch (Exception e) {
                log.error("Failed to load plugin: {}", plugin.name(), e);
            }
        }
        log.info("Loaded {} plugin(s)", plugins.size());
    }

    public void unloadPlugins() {
        for (AssistantPlugin plugin : plugins) {
            try {
                plugin.onUnload();
                log.info("Unloaded plugin: {}", plugin.name());
            } catch (Exception e) {
                log.error("Failed to unload plugin: {}", plugin.name(), e);
            }
        }
        plugins.clear();
    }

    public List<AssistantPlugin> plugins() {
        return Collections.unmodifiableList(plugins);
    }

    public void registerPlugin(AssistantPlugin plugin) {
        AssistantPlugin.PluginContext context = new DefaultPluginContext();
        plugin.onLoad(context);
        plugins.add(plugin);
        log.info("Registered plugin: {} v{}", plugin.name(), plugin.version());
    }

    private class DefaultPluginContext implements AssistantPlugin.PluginContext {
        @Override
        public ToolRegistry toolRegistry() {
            return toolRegistry;
        }

        @Override
        public SkillRegistry skillRegistry() {
            return skillRegistry;
        }

        @Override
        public Path dataDir() {
            return dataDir;
        }
    }
}
