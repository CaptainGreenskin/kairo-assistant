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

import io.kairo.api.hook.OnComplete;
import io.kairo.api.hook.OnError;
import io.kairo.api.hook.OnSessionEnd;
import io.kairo.api.hook.OnSessionStart;
import io.kairo.api.hook.PostActing;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PreActing;
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.hook.SessionEndEvent;
import io.kairo.api.hook.SessionStartEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hook handler registered with the agent's {@link io.kairo.api.hook.HookChain} that bridges
 * Kairo's lifecycle annotations onto Claude Code's plugin hook event names, dispatching each
 * matching event through {@link PluginHookCatalog#dispatch}.
 *
 * <p>Mapping (kairo → Claude Code plugin event):
 *
 * <ul>
 *   <li>{@code @OnSessionStart} → {@code SessionStart}
 *   <li>{@code @OnSessionEnd} → {@code SessionEnd}
 *   <li>{@code @PreActing}    → {@code PreToolUse}
 *   <li>{@code @PostActing}   → {@code PostToolUse}
 *   <li>{@code @OnComplete}   → {@code Stop}
 *   <li>{@code @OnError}      → {@code Notification} (closest CC analogue)
 * </ul>
 *
 * <p>This bridge fires hooks <strong>fire-and-forget</strong> — i.e. it doesn't block the agent
 * loop on a plugin hook's reply. Honouring hook decisions (cancel / modify) is intentionally
 * deferred until {@link PluginHookCatalog#dispatch} reports {@code decision} JSON consistently,
 * which today's prompt/agent/mcp_tool handlers don't yet. Logging on hook failure keeps debugging
 * cheap; we never break the user-facing agent because a plugin hook threw.
 *
 * <p>Use {@link io.kairo.core.agent.AgentBuilder#hook(Object)} to register an instance.
 */
public final class PluginHookBridge {

    private static final Logger log = LoggerFactory.getLogger(PluginHookBridge.class);

    /** Cap dispatched-hook execution at this duration; otherwise a slow hook stalls the agent. */
    private static final Duration DISPATCH_TIMEOUT = Duration.ofSeconds(15);

    private final PluginHookCatalog catalog;

    public PluginHookBridge(PluginHookCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @OnSessionStart
    public SessionStartEvent onSessionStart(SessionStartEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentName", event.agentName());
        payload.put("modelName", event.modelName());
        payload.put("maxIterations", event.maxIterations());
        fire("SessionStart", payload);
        return event;
    }

    @OnSessionEnd
    public SessionEndEvent onSessionEnd(SessionEndEvent event) {
        fire("SessionEnd", Map.of("agentName", String.valueOf(event)));
        return event;
    }

    @PreActing
    public PreActingEvent onPreActing(PreActingEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tool_name", event.toolName());
        payload.put("tool_input", event.input());
        fire("PreToolUse", payload);
        return event;
    }

    @PostActing
    public PostActingEvent onPostActing(PostActingEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tool_name", event.toolName());
        if (event.result() != null) {
            payload.put("tool_result", String.valueOf(event.result()));
        }
        fire("PostToolUse", payload);
        return event;
    }

    @OnComplete
    public Object onComplete(Object event) {
        fire("Stop", Map.of());
        return event;
    }

    @OnError
    public Object onError(Object event) {
        fire("Notification", Map.of("message", "agent error"));
        return event;
    }

    private void fire(String eventName, Map<String, Object> payload) {
        try {
            catalog.dispatch(eventName, payload)
                    .timeout(DISPATCH_TIMEOUT)
                    .doOnError(
                            err ->
                                    log.warn(
                                            "Plugin hook '{}' dispatch failed (continuing): {}",
                                            eventName,
                                            err.getMessage()))
                    .onErrorComplete()
                    .subscribe();
        } catch (Exception e) {
            // Catalog could throw synchronously if the executor is misconfigured — never let that
            // break the agent loop.
            log.warn("Plugin hook bridge '{}' synchronous error: {}", eventName, e.getMessage());
        }
    }
}
