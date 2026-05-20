package io.kairo.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginScope;
import io.kairo.api.plugin.PluginSource;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.plugin.ComponentRegistrar;
import io.kairo.plugin.DefaultPluginManager;
import io.kairo.plugin.DefaultPluginRegistry;
import io.kairo.plugin.KairoComponentRegistrar;
import io.kairo.plugin.PluginEnvironment;
import io.kairo.plugin.PluginLoader;
import io.kairo.plugin.source.LocalPathSourceFetcher;
import io.kairo.plugin.source.SourceFetcherRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end Phase D dogfood test: a Claude-Code-format plugin lives on disk inside the
 * assistant's data directory, gets installed + enabled through the new {@code PluginManager},
 * and contributes a skill to the assistant's {@link io.kairo.api.skill.SkillRegistry}.
 *
 * <p>This is the smallest realistic acceptance test for the assistant: prove that a plugin tree
 * authored using the published file format reaches the running agent's skill catalog without any
 * Java SPI implementation work.
 */
class PluginIntegrationTest {

    @Test
    void localPluginContributesSkillToAssistantRegistry(@TempDir Path tmp) throws Exception {
        // 1. Author a tiny plugin on disk.
        Path pluginDir = tmp.resolve("hello-plugin");
        Files.createDirectories(pluginDir.resolve(".kairo-plugin"));
        Files.writeString(
                pluginDir.resolve(".kairo-plugin/plugin.json"),
                """
                {
                  "name": "hello",
                  "version": "1.0.0",
                  "description": "A hello-world skill bundled as a Kairo plugin"
                }
                """);
        Files.createDirectories(pluginDir.resolve("skills/hello"));
        Files.writeString(
                pluginDir.resolve("skills/hello/SKILL.md"),
                """
                ---
                name: hello
                description: Greet the user politely
                ---

                Say hello to the user.
                """);

        // 2. Stand up the wiring — same shape AssistantAgentFactory uses in production.
        var skillRegistry = AssistantSkills.createRegistry();
        var environment = new PluginEnvironment();
        ComponentRegistrar registrar =
                new KairoComponentRegistrar(skillRegistry, null, environment);
        var fetchers = new SourceFetcherRegistry().register(new LocalPathSourceFetcher());
        var manager =
                new DefaultPluginManager(
                        new DefaultPluginRegistry(),
                        new PluginLoader(),
                        tmp.resolve("data"),
                        registrar,
                        fetchers);

        // 3. install + enable through the public API the user would invoke from /plugin install.
        PluginInstallation installed =
                manager.install(new PluginSource.LocalPath(pluginDir), PluginScope.PROJECT)
                        .block(Duration.ofSeconds(10));
        manager.enable(installed.id()).block(Duration.ofSeconds(10));

        // 4. Verify the assistant's skill registry now contains the plugin's skill.
        assertThat(skillRegistry.list())
                .as("plugin SKILL.md must reach the assistant's skill registry")
                .extracting(io.kairo.api.skill.SkillDefinition::name)
                .contains("hello");

        // 5. Disable should remove the contribution; uninstall should clear the registry.
        manager.disable(installed.id()).block(Duration.ofSeconds(5));
        manager.uninstall(installed.id()).block(Duration.ofSeconds(5));
        assertThat(manager.list()).isEmpty();
    }

    @Test
    void multiplePluginsCoexistInOneRegistry(@TempDir Path tmp) throws Exception {
        var skillRegistry = AssistantSkills.createRegistry();
        var manager =
                new DefaultPluginManager(
                        new DefaultPluginRegistry(),
                        new PluginLoader(),
                        tmp.resolve("data"),
                        new KairoComponentRegistrar(skillRegistry, null, new PluginEnvironment()),
                        new SourceFetcherRegistry().register(new LocalPathSourceFetcher()));

        for (String name : new String[] {"alpha", "beta"}) {
            Path dir = tmp.resolve(name);
            Files.createDirectories(dir.resolve(".kairo-plugin"));
            Files.writeString(
                    dir.resolve(".kairo-plugin/plugin.json"),
                    "{\"name\":\"" + name + "\",\"version\":\"1.0.0\"}");
            Files.createDirectories(dir.resolve("skills/" + name));
            Files.writeString(
                    dir.resolve("skills/" + name + "/SKILL.md"),
                    "---\nname: " + name + "\ndescription: " + name + " skill\n---\n\n" + name);
            var inst =
                    manager.install(new PluginSource.LocalPath(dir), PluginScope.USER)
                            .block(Duration.ofSeconds(10));
            manager.enable(inst.id()).block(Duration.ofSeconds(5));
        }

        assertThat(manager.list()).hasSize(2);
        assertThat(skillRegistry.list())
                .extracting(io.kairo.api.skill.SkillDefinition::name)
                .contains("alpha", "beta");
    }
}
