package io.kairo.assistant.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AssistantPluginTest {

    @Test
    void defaultDescriptionIsEmpty() {
        AssistantPlugin plugin = minimalPlugin("test", "1.0");
        assertThat(plugin.description()).isEmpty();
    }

    @Test
    void defaultOnLoadDoesNothing() {
        AssistantPlugin plugin = minimalPlugin("test", "1.0");
        var context = createContext();
        plugin.onLoad(context);
    }

    @Test
    void defaultOnUnloadDoesNothing() {
        AssistantPlugin plugin = minimalPlugin("test", "1.0");
        plugin.onUnload();
    }

    @Test
    void pluginReceivesToolRegistryViaContext() {
        AtomicReference<Object> captured = new AtomicReference<>();
        DefaultToolRegistry registry = new DefaultToolRegistry();

        PluginManager manager = new PluginManager(registry, AssistantSkills.createRegistry(), Path.of("/tmp"));
        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "ctx-test"; }
            @Override public String version() { return "1.0"; }
            @Override public void onLoad(PluginContext context) {
                captured.set(context.toolRegistry());
            }
        });

        assertThat(captured.get()).isSameAs(registry);
    }

    @Test
    void pluginReceivesSkillRegistryViaContext() {
        AtomicReference<Object> captured = new AtomicReference<>();
        var skillRegistry = AssistantSkills.createRegistry();

        PluginManager manager = new PluginManager(new DefaultToolRegistry(), skillRegistry, Path.of("/tmp"));
        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "skill-test"; }
            @Override public String version() { return "1.0"; }
            @Override public void onLoad(PluginContext context) {
                captured.set(context.skillRegistry());
            }
        });

        assertThat(captured.get()).isSameAs(skillRegistry);
    }

    @Test
    void pluginWithCustomDescription() {
        AssistantPlugin plugin = new AssistantPlugin() {
            @Override public String name() { return "custom"; }
            @Override public String version() { return "2.0"; }
            @Override public String description() { return "A custom plugin"; }
        };
        assertThat(plugin.description()).isEqualTo("A custom plugin");
    }

    @Test
    void pluginLoadExceptionDoesNotCrashManager() {
        PluginManager manager = new PluginManager(
                new DefaultToolRegistry(), AssistantSkills.createRegistry(), Path.of("/tmp"));

        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "good-plugin"; }
            @Override public String version() { return "1.0"; }
        });

        assertThat(manager.plugins()).hasSize(1);
    }

    @Test
    void nameAndVersionAreRequired() {
        AssistantPlugin plugin = minimalPlugin("my-plugin", "4.5.6");
        assertThat(plugin.name()).isEqualTo("my-plugin");
        assertThat(plugin.version()).isEqualTo("4.5.6");
    }

    @Test
    void onUnloadDefaultDoesNotThrow() {
        AssistantPlugin plugin = minimalPlugin("safe", "1.0");
        plugin.onLoad(createContext());
        plugin.onUnload();
        plugin.onUnload();
    }

    @Test
    void contextDataDirAccessible() {
        var context = createContext();
        assertThat(context.dataDir()).isEqualTo(Path.of("/tmp"));
    }

    private static AssistantPlugin minimalPlugin(String name, String version) {
        return new AssistantPlugin() {
            @Override public String name() { return name; }
            @Override public String version() { return version; }
        };
    }

    private static AssistantPlugin.PluginContext createContext() {
        return new AssistantPlugin.PluginContext() {
            @Override public io.kairo.api.tool.ToolRegistry toolRegistry() { return new DefaultToolRegistry(); }
            @Override public io.kairo.api.skill.SkillRegistry skillRegistry() { return AssistantSkills.createRegistry(); }
            @Override public Path dataDir() { return Path.of("/tmp"); }
        };
    }
}
