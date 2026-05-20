/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginScope;
import io.kairo.api.plugin.PluginSource;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.plugin.ComponentRegistrar;
import io.kairo.plugin.DefaultPluginManager;
import io.kairo.plugin.DefaultPluginRegistry;
import io.kairo.plugin.KairoComponentRegistrar;
import io.kairo.plugin.PluginEnvironment;
import io.kairo.plugin.PluginLoader;
import io.kairo.plugin.source.LocalPathSourceFetcher;
import io.kairo.plugin.source.SourceFetcherRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the end-to-end path for a plugin that ships <em>both</em> a {@code SKILL.md} and a script
 * the skill references via {@code ${CLAUDE_PLUGIN_ROOT}}: install the plugin, enable it, then
 * confirm:
 *
 * <ol>
 *   <li>The skill ends up in the assistant's {@code SkillRegistry}.
 *   <li>The skill instructions reach the registry with the variable still intact so the agent's
 *       prompt-time renderer can substitute it (the loader passes instructions through verbatim;
 *       substitution is the runtime's job).
 *   <li>The referenced script is shipped on disk at the resolved path and is executable — i.e. if
 *       the agent's Bash tool ever runs it, it'll work.
 * </ol>
 *
 * <p>POSIX-only: the fixture script is bash and we need to preserve the exec bit through {@link
 * io.kairo.plugin.installer.PluginCacheManager}'s copy.
 */
@DisabledOnOs(OS.WINDOWS)
class SkillWithScriptsTest {

    @Test
    void skillReferencingScriptLoadsAndScriptIsExecutable(@TempDir Path tmp) throws Exception {
        // 1. Author a plugin with one skill that points at scripts/audit.sh
        Path pluginDir = tmp.resolve("audit-plugin");
        Files.createDirectories(pluginDir.resolve(".kairo-plugin"));
        Files.writeString(
                pluginDir.resolve(".kairo-plugin/plugin.json"),
                """
                {
                  "name": "audit-plugin",
                  "version": "1.0.0",
                  "description": "Skill that drives an external bash script"
                }
                """);
        Files.createDirectories(pluginDir.resolve("skills/audit"));
        Files.writeString(
                pluginDir.resolve("skills/audit/SKILL.md"),
                """
                ---
                name: audit
                description: Run the audit script against the current directory
                ---

                Run `${CLAUDE_PLUGIN_ROOT}/scripts/audit.sh "$@"`.
                """);
        Files.createDirectories(pluginDir.resolve("scripts"));
        Path script = pluginDir.resolve("scripts/audit.sh");
        Files.writeString(
                script,
                """
                #!/usr/bin/env bash
                echo "audit:$*"
                """);
        Files.setPosixFilePermissions(
                script,
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE));

        // 2. Stand up the production wiring shape (no MCP, no subagents needed for this test).
        var skillRegistry = AssistantSkills.createRegistry();
        ComponentRegistrar registrar =
                new KairoComponentRegistrar(skillRegistry, null, new PluginEnvironment(), null);
        var manager =
                new DefaultPluginManager(
                        new DefaultPluginRegistry(),
                        new PluginLoader(),
                        tmp.resolve("data"),
                        registrar,
                        new SourceFetcherRegistry().register(new LocalPathSourceFetcher()));

        // 3. Install + enable.
        PluginInstallation inst =
                manager.install(new PluginSource.LocalPath(pluginDir), PluginScope.USER)
                        .block(Duration.ofSeconds(10));
        manager.enable(inst.id()).block(Duration.ofSeconds(10));

        // 4. Skill is in the assistant's registry with instructions preserved verbatim
        //    (variable substitution happens at prompt-render time, not at load time).
        SkillDefinition audit =
                skillRegistry.list().stream()
                        .filter(s -> s.name().equals("audit"))
                        .findFirst()
                        .orElseThrow();
        assertThat(audit.instructions())
                .as("plugin skill instructions reach the registry intact")
                .contains("${CLAUDE_PLUGIN_ROOT}/scripts/audit.sh");

        // 5. The script the skill references is on disk at the install path and is executable.
        Path resolvedScript = inst.rootPath().resolve("scripts/audit.sh");
        assertThat(Files.isRegularFile(resolvedScript)).isTrue();
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(resolvedScript);
        assertThat(perms)
                .as("execute bit must survive the install path so Bash tool can run the script")
                .contains(PosixFilePermission.OWNER_EXECUTE);

        // 6. Bonus: actually fork it so we know the path works end-to-end through the OS.
        ProcessBuilder pb =
                new ProcessBuilder(resolvedScript.toAbsolutePath().toString(), "arg-from-test")
                        .redirectErrorStream(true);
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        assertThat(stdout.trim()).isEqualTo("audit:arg-from-test");
    }

    @SuppressWarnings("unused")
    private static void unused() throws IOException {
        // Suppress IOException unused warning on JDK source filter.
    }
}
