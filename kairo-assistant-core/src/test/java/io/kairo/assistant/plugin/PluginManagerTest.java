package io.kairo.assistant.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolRegistry;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.tool.DefaultToolRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PluginManagerTest {

    @Test
    void loadAndUnloadPlugins() {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        SkillRegistry skillRegistry = AssistantSkills.createRegistry();
        PluginManager manager = new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp"));

        manager.loadPlugins();
        assertThat(manager.plugins()).isEmpty();
        manager.unloadPlugins();
    }

    @Test
    void registerPluginManually() {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        SkillRegistry skillRegistry = AssistantSkills.createRegistry();
        PluginManager manager = new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp"));

        AtomicBoolean loaded = new AtomicBoolean(false);
        AtomicBoolean unloaded = new AtomicBoolean(false);

        AssistantPlugin testPlugin = new AssistantPlugin() {
            @Override
            public String name() { return "test-plugin"; }

            @Override
            public String version() { return "1.0"; }

            @Override
            public void onLoad(PluginContext context) {
                loaded.set(true);
                assertThat(context.toolRegistry()).isNotNull();
                assertThat(context.skillRegistry()).isNotNull();
                assertThat(context.dataDir()).isNotNull();
            }

            @Override
            public void onUnload() {
                unloaded.set(true);
            }
        };

        manager.registerPlugin(testPlugin);
        assertThat(loaded.get()).isTrue();
        assertThat(manager.plugins()).hasSize(1);
        assertThat(manager.plugins().get(0).name()).isEqualTo("test-plugin");

        manager.unloadPlugins();
        assertThat(unloaded.get()).isTrue();
        assertThat(manager.plugins()).isEmpty();
    }

    @Test
    void pluginsListIsUnmodifiable() {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        SkillRegistry skillRegistry = AssistantSkills.createRegistry();
        PluginManager manager = new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp"));

        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "p1"; }
            @Override public String version() { return "1.0"; }
        });

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> manager.plugins().add(new AssistantPlugin() {
                    @Override public String name() { return "p2"; }
                    @Override public String version() { return "1.0"; }
                }));
    }

    @Test
    void multiplePluginsCanBeRegistered() {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        SkillRegistry skillRegistry = AssistantSkills.createRegistry();
        PluginManager manager = new PluginManager(toolRegistry, skillRegistry, Path.of("/tmp"));

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            manager.registerPlugin(new AssistantPlugin() {
                @Override public String name() { return "plugin-" + idx; }
                @Override public String version() { return "1." + idx; }
            });
        }
        assertThat(manager.plugins()).hasSize(5);
    }

    @Test
    void pluginDefaultDescriptionIsEmpty() {
        AssistantPlugin p = new AssistantPlugin() {
            @Override public String name() { return "desc-test"; }
            @Override public String version() { return "1.0"; }
        };
        assertThat(p.description()).isEmpty();
    }

    @Test
    void pluginContextProvidesDataDir() {
        AtomicBoolean verified = new AtomicBoolean(false);
        Path dataDir = Path.of("/tmp/test-data");
        PluginManager manager = new PluginManager(
                new DefaultToolRegistry(),
                AssistantSkills.createRegistry(),
                dataDir);

        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "dir-test"; }
            @Override public String version() { return "1.0"; }
            @Override public void onLoad(PluginContext context) {
                verified.set(context.dataDir().equals(dataDir));
            }
        });
        assertThat(verified.get()).isTrue();
    }

    @Test
    void unloadCallsOnUnloadForEachPlugin() {
        AtomicBoolean unloaded1 = new AtomicBoolean(false);
        AtomicBoolean unloaded2 = new AtomicBoolean(false);
        PluginManager manager = new PluginManager(
                new DefaultToolRegistry(), AssistantSkills.createRegistry(), Path.of("/tmp"));

        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "p1"; }
            @Override public String version() { return "1.0"; }
            @Override public void onUnload() { unloaded1.set(true); }
        });
        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "p2"; }
            @Override public String version() { return "1.0"; }
            @Override public void onUnload() { unloaded2.set(true); }
        });

        manager.unloadPlugins();
        assertThat(unloaded1.get()).isTrue();
        assertThat(unloaded2.get()).isTrue();
        assertThat(manager.plugins()).isEmpty();
    }

    @Test
    void pluginRegistrationOrderPreserved() {
        PluginManager manager = new PluginManager(
                new DefaultToolRegistry(), AssistantSkills.createRegistry(), Path.of("/tmp"));

        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "alpha"; }
            @Override public String version() { return "1.0"; }
        });
        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "beta"; }
            @Override public String version() { return "2.0"; }
        });
        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "gamma"; }
            @Override public String version() { return "3.0"; }
        });

        assertThat(manager.plugins().get(0).name()).isEqualTo("alpha");
        assertThat(manager.plugins().get(1).name()).isEqualTo("beta");
        assertThat(manager.plugins().get(2).name()).isEqualTo("gamma");
    }

    @Test
    void pluginVersionAccessible() {
        PluginManager manager = new PluginManager(
                new DefaultToolRegistry(), AssistantSkills.createRegistry(), Path.of("/tmp"));

        manager.registerPlugin(new AssistantPlugin() {
            @Override public String name() { return "versioned"; }
            @Override public String version() { return "3.2.1"; }
        });

        assertThat(manager.plugins().get(0).version()).isEqualTo("3.2.1");
    }

    @Test
    void loadPluginsWithNoServiceProviderIsEmpty() {
        PluginManager manager = new PluginManager(
                new DefaultToolRegistry(), AssistantSkills.createRegistry(), Path.of("/tmp"));
        manager.loadPlugins();
        assertThat(manager.plugins()).isEmpty();
    }
}
