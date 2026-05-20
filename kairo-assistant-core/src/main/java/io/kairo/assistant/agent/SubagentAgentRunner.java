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
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolRegistry;
import io.kairo.core.agent.AgentBuilder;
import io.kairo.plugin.hook.handlers.AgentHookActionHandler.AgentRunner;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link AgentRunner} implementation that spawns a fresh, short-lived sub-agent for every plugin
 * hook {@code agent} action. Reuses the assistant's {@link ModelProvider} + tool catalog so the
 * sub-agent has the same capabilities as the parent, but starts with a clean conversation so hook
 * prompts can't pollute the user-facing session.
 *
 * <p>Bounded by {@code maxIterations=5} and a {@code Duration} timeout — hook actions are meant to
 * be quick guards/auditors, not long-running tasks. Callers wrap us in
 * {@link AgentHookActionHandler}'s own 60-second timeout, so this is belt + suspenders.
 *
 * <p>The implementation is intentionally simple: build an {@link Agent} via {@link AgentBuilder},
 * send the prompt as a user message, take the agent's reply text as the result. Failures surface
 * as the Mono's error signal; {@link AgentHookActionHandler} routes those into a
 * {@link io.kairo.plugin.hook.HookExecutor.HookResult#error}.
 */
public final class SubagentAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SubagentAgentRunner.class);

    private static final int DEFAULT_MAX_ITERATIONS = 5;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);

    private final ModelProvider modelProvider;
    private final String defaultModelName;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final String systemPrompt;
    private final PluginAuditLogger audit;

    public SubagentAgentRunner(
            ModelProvider modelProvider,
            String defaultModelName,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            String systemPrompt) {
        this(modelProvider, defaultModelName, toolRegistry, toolExecutor, systemPrompt, null);
    }

    public SubagentAgentRunner(
            ModelProvider modelProvider,
            String defaultModelName,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            String systemPrompt,
            PluginAuditLogger audit) {
        this.modelProvider = modelProvider;
        this.defaultModelName = defaultModelName;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.systemPrompt =
                systemPrompt == null
                        ? "You are a Kairo plugin hook sub-agent. Reason briefly and return"
                                + " a concise final answer."
                        : systemPrompt;
        this.audit = audit;
    }

    @Override
    public Mono<String> run(String prompt, String modelHint) {
        if (prompt == null || prompt.isBlank()) {
            return Mono.just("");
        }
        String modelName =
                (modelHint == null || modelHint.isBlank()) ? defaultModelName : modelHint;
        if (audit != null) {
            try {
                audit.recordSubagentDispatch("<bridge>", prompt, modelName);
            } catch (Exception e) {
                log.debug("Audit log write skipped for sub-agent dispatch: {}", e.getMessage());
            }
        }
        return Mono.fromCallable(
                        () -> {
                            Agent agent =
                                    AgentBuilder.create()
                                            .name("plugin-hook-subagent")
                                            .model(modelProvider)
                                            .modelName(modelName)
                                            .tools(toolRegistry)
                                            .toolExecutor(toolExecutor)
                                            .systemPrompt(systemPrompt)
                                            .maxIterations(DEFAULT_MAX_ITERATIONS)
                                            .streaming(false)
                                            .build();
                            return agent;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        agent ->
                                agent.call(Msg.of(MsgRole.USER, prompt))
                                        .map(
                                                reply -> {
                                                    String text = reply == null ? "" : reply.text();
                                                    return text == null ? "" : text;
                                                })
                                        .doFinally(
                                                sig -> {
                                                    try {
                                                        agent.interrupt();
                                                    } catch (Exception e) {
                                                        log.debug(
                                                                "sub-agent interrupt failed: {}",
                                                                e.getMessage());
                                                    }
                                                }))
                .timeout(DEFAULT_TIMEOUT)
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "Plugin hook sub-agent failed: {}", err.getMessage());
                            return Mono.error(err);
                        });
    }
}
