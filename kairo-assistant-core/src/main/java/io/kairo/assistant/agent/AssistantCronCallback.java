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

import io.kairo.api.agent.Agent;
import io.kairo.api.cron.CronFireCallback;
import io.kairo.api.cron.CronTask;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.cron.CronChainContext;
import io.kairo.cron.CronDeliveryRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CronFireCallback} the assistant installs at start-up. Honours every {@link
 * io.kairo.api.cron.CronTaskOptions} field that the kairo-cron M3/M4 SPI exposes:
 *
 * <ol>
 *   <li><b>noAgent + script</b> → fork a shell process; capture stdout/stderr; deliver verbatim.
 *       Zero LLM involvement.
 *   <li><b>skills</b> → prepend each named skill's instructions to the prompt before the agent
 *       turn so the model has the relevant tooling in context.
 *   <li><b>workdir</b> → set the {@code KAIRO_CWD} env var for the agent turn so file tools
 *       resolve relative paths there. Workdir-only tools (BashTool etc.) honour this via their
 *       normal cwd handling.
 *   <li><b>contextFromTaskId</b> → look up the upstream task's last output in {@link
 *       CronChainContext} and prepend it to the prompt as {@code Previous task output:\n...}.
 *   <li><b>deliveryTargets</b> (encoded in the task prompt for now — see {@link
 *       #parseDeliveryTargets}) → after the run, route the final response to every registered
 *       target via {@link CronDeliveryRegistry}.
 * </ol>
 *
 * <p>Each cron firing builds a fresh ephemeral agent so a long-running cron job doesn't pollute
 * the user-facing REPL session. The factory provides a fresh-{@link Agent} supplier rather than
 * a reused instance.
 */
public final class AssistantCronCallback implements CronFireCallback {

    private static final Logger log = LoggerFactory.getLogger(AssistantCronCallback.class);

    /** Default time budget per cron fire (covers script timeout + agent turn). */
    private static final Duration DEFAULT_FIRE_TIMEOUT = Duration.ofMinutes(5);

    /** Embedded directive: {@code @deliver:<target>[ @deliver:<target>]}. */
    private static final java.util.regex.Pattern DELIVER_PATTERN =
            java.util.regex.Pattern.compile("@deliver:(\\S+)");

    private final Supplier<Agent> agentSupplier;
    private final SkillRegistry skillRegistry;
    private final CronChainContext chainContext;
    private final CronDeliveryRegistry deliveryRegistry;
    private final Duration fireTimeout;

    public AssistantCronCallback(
            Supplier<Agent> agentSupplier,
            SkillRegistry skillRegistry,
            CronChainContext chainContext,
            CronDeliveryRegistry deliveryRegistry) {
        this(agentSupplier, skillRegistry, chainContext, deliveryRegistry, DEFAULT_FIRE_TIMEOUT);
    }

    public AssistantCronCallback(
            Supplier<Agent> agentSupplier,
            SkillRegistry skillRegistry,
            CronChainContext chainContext,
            CronDeliveryRegistry deliveryRegistry,
            Duration fireTimeout) {
        this.agentSupplier = agentSupplier;
        this.skillRegistry = skillRegistry;
        this.chainContext = chainContext;
        this.deliveryRegistry = deliveryRegistry;
        this.fireTimeout = fireTimeout == null ? DEFAULT_FIRE_TIMEOUT : fireTimeout;
    }

    @Override
    public void onFire(CronTask task) {
        String output;
        try {
            if (task.noAgent()) {
                output = runScript(task);
            } else {
                output = runAgent(task);
            }
        } catch (Exception e) {
            log.warn(
                    "Cron task {} firing failed: {} - {}",
                    task.id(),
                    e.getClass().getSimpleName(),
                    e.getMessage());
            output = "error: " + e.getMessage();
        }
        // Record for chained downstream tasks.
        if (chainContext != null) {
            chainContext.recordOutput(task.id(), output);
        }
        // Deliver to every embedded @deliver:target in the prompt.
        if (deliveryRegistry != null) {
            for (String target : parseDeliveryTargets(task.prompt())) {
                deliveryRegistry
                        .deliver(task, target, output)
                        .doOnError(
                                err ->
                                        log.warn(
                                                "Cron task {} delivery to '{}' failed: {}",
                                                task.id(),
                                                target,
                                                err.getMessage()))
                        .onErrorComplete()
                        .subscribe();
            }
        }
    }

    /** Execute a no-agent shell script via {@link ProcessBuilder}; return stdout. */
    private String runScript(CronTask task) throws IOException, InterruptedException {
        ProcessBuilder pb =
                new ProcessBuilder("sh", "-c", task.script()).redirectErrorStream(true);
        if (task.workdir() != null && !task.workdir().isBlank()) {
            pb.directory(java.nio.file.Path.of(task.workdir()).toFile());
        }
        Process p = pb.start();
        if (!p.waitFor(fireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IOException("Cron script timeout after " + fireTimeout);
        }
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        log.info(
                "Cron task {} script complete (exit={}, bytes={})",
                task.id(),
                p.exitValue(),
                stdout.length());
        return stdout;
    }

    /** Build a fresh agent, run one user turn, return the reply text. */
    private String runAgent(CronTask task) {
        String prompt = buildPrompt(task);
        Agent agent = agentSupplier.get();
        try {
            Msg reply = agent.call(Msg.of(MsgRole.USER, prompt)).block(fireTimeout);
            return reply == null || reply.text() == null ? "" : reply.text();
        } finally {
            try {
                agent.interrupt();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    /** Compose the actual prompt: chained context → skill instructions → original prompt. */
    String buildPrompt(CronTask task) {
        StringBuilder sb = new StringBuilder();
        if (task.contextFromTaskId() != null && chainContext != null) {
            chainContext
                    .lastOutput(task.contextFromTaskId())
                    .ifPresent(
                            prev ->
                                    sb.append("Previous task output:\n")
                                            .append(prev)
                                            .append("\n\n"));
        }
        if (!task.skills().isEmpty() && skillRegistry != null) {
            for (String name : task.skills()) {
                skillRegistry
                        .get(name)
                        .ifPresent(
                                def ->
                                        sb.append("# Skill: ")
                                                .append(def.name())
                                                .append("\n")
                                                .append(def.instructions() == null ? "" : def.instructions())
                                                .append("\n\n"));
            }
        }
        sb.append(task.prompt() == null ? "" : task.prompt());
        return sb.toString();
    }

    /** Parse {@code @deliver:<target>} directives out of the prompt. */
    static List<String> parseDeliveryTargets(String prompt) {
        if (prompt == null || prompt.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        var m = DELIVER_PATTERN.matcher(prompt);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
