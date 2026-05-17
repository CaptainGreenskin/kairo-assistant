package io.kairo.assistant.plugin;

import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolRegistry;

public interface AssistantPlugin {

    String name();

    String version();

    default String description() {
        return "";
    }

    default void onLoad(PluginContext context) {}

    default void onUnload() {}

    interface PluginContext {
        ToolRegistry toolRegistry();

        SkillRegistry skillRegistry();

        java.nio.file.Path dataDir();
    }
}
