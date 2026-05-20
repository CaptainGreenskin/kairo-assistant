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

import io.kairo.api.hook.NotificationEvent;
import io.kairo.api.hook.OnComplete;
import io.kairo.api.hook.OnError;
import io.kairo.api.hook.OnNotification;
import io.kairo.api.hook.OnSessionEnd;
import io.kairo.api.hook.OnSessionStart;
import io.kairo.api.hook.OnSubagentStart;
import io.kairo.api.hook.OnSubagentStop;
import io.kairo.api.hook.OnUserPromptSubmit;
import io.kairo.api.hook.PostActing;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PreActing;
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.hook.SessionEndEvent;
import io.kairo.api.hook.SessionStartEvent;
import io.kairo.api.hook.SubagentStartEvent;
import io.kairo.api.hook.SubagentStopEvent;
import io.kairo.api.hook.UserPromptSubmitEvent;
import io.kairo.plugin.hook.HookExecutor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hook handler registered with the agent's {@link io.kairo.api.hook.HookChain} that bridges
 * Kairo's lifecycle annotations onto Claude Code's plugin hook event names, dispatching each
 * matching event through {@link PluginHookCatalog#dispatch} and honouring decisions returned by
 * plugin hooks.
 *
 * <h2>Decision honouring (Claude Code semantics)</h2>
 *
 * <p>Each hook handler can write a JSON object to stdout (for {@code command} actions) or return
 * one (for {@code prompt} / {@code agent}). The bridge parses the {@code decision} map of every
 * dispatched action and merges them with these rules:
 *
 * <ul>
 *   <li>{@code "continue": false}, {@code "block": true}, or {@code "deny": ...} on a
 *       {@code PreToolUse} event → mark the {@link PreActingEvent} as cancelled, stopping the
 *       tool call.
 *   <li>{@code "tool_input": {...}} on {@code PreToolUse} → replace the tool's input map.
 *   <li>{@code "modifiedPrompt": "..."} or {@code "prompt": "..."} on {@code UserPromptSubmit} →
 *       replace the user's prompt text.
 * </ul>
 *
 * <p>Multiple plugins firing on the same event compose by latest-wins for prompt/input rewrites,
 * any-cancel-wins for cancellation.
 *
 * <h2>Pre/post split</h2>
 *
 * <ul>
 *   <li>{@code Pre*} hooks (PreToolUse, UserPromptSubmit) block the agent on the dispatch until
 *       all plugin actions complete (capped at {@link #DECISION_TIMEOUT}). Honouring a
 *       decision requires waiting for it.
 *   <li>{@code Post*} hooks (PostToolUse, SessionStart, SessionEnd, Stop, Notification) are
 *       fire-and-forget — plugin handlers can react to past events but can't undo them.
 * </ul>
 */
public final class PluginHookBridge {

    private static final Logger log = LoggerFactory.getLogger(PluginHookBridge.class);

    private final PluginHookCatalog catalog;
    private final HookTimeoutConfig timeouts;

    public PluginHookBridge(PluginHookCatalog catalog) {
        this(catalog, HookTimeoutConfig.fromEnvironment());
    }

    public PluginHookBridge(PluginHookCatalog catalog, HookTimeoutConfig timeouts) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.timeouts = timeouts == null ? HookTimeoutConfig.defaults() : timeouts;
    }

    // ── Pre* events: block on decisions ─────────────────────────────────────

    @PreActing
    public PreActingEvent onPreActing(PreActingEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tool_name", event.toolName());
        payload.put("tool_input", event.input());
        Decision decision = collectDecisions("PreToolUse", payload);
        if (decision.cancel) {
            return new PreActingEvent(event.toolName(), event.input(), true);
        }
        if (decision.replacementToolInput != null) {
            return new PreActingEvent(event.toolName(), decision.replacementToolInput, event.cancelled());
        }
        return event;
    }

    @OnUserPromptSubmit
    public UserPromptSubmitEvent onUserPromptSubmit(UserPromptSubmitEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("session_id", event.sessionId());
        payload.put("prompt", event.prompt());
        payload.put("cwd", event.cwd());
        Decision decision = collectDecisions("UserPromptSubmit", payload);
        if (decision.replacementPrompt != null) {
            return new UserPromptSubmitEvent(
                    event.sessionId(), decision.replacementPrompt, event.cwd());
        }
        return event;
    }

    // ── Post* events: fire-and-forget ───────────────────────────────────────

    @OnSessionStart
    public SessionStartEvent onSessionStart(SessionStartEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentName", event.agentName());
        payload.put("modelName", event.modelName());
        payload.put("maxIterations", event.maxIterations());
        fireAndForget("SessionStart", payload);
        return event;
    }

    @OnSessionEnd
    public SessionEndEvent onSessionEnd(SessionEndEvent event) {
        fireAndForget("SessionEnd", Map.of("event", String.valueOf(event)));
        return event;
    }

    @PostActing
    public PostActingEvent onPostActing(PostActingEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tool_name", event.toolName());
        if (event.result() != null) {
            payload.put("tool_result", String.valueOf(event.result()));
        }
        fireAndForget("PostToolUse", payload);
        return event;
    }

    @OnComplete
    public Object onComplete(Object event) {
        fireAndForget("Stop", Map.of());
        return event;
    }

    @OnError
    public Object onError(Object event) {
        fireAndForget("Notification", Map.of("severity", "error"));
        return event;
    }

    @OnNotification
    public NotificationEvent onNotification(NotificationEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", String.valueOf(event));
        fireAndForget("Notification", payload);
        return event;
    }

    @OnSubagentStart
    public SubagentStartEvent onSubagentStart(SubagentStartEvent event) {
        fireAndForget("SubagentStart", Map.of("event", String.valueOf(event)));
        return event;
    }

    @OnSubagentStop
    public SubagentStopEvent onSubagentStop(SubagentStopEvent event) {
        fireAndForget("SubagentStop", Map.of("event", String.valueOf(event)));
        return event;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Block on every dispatched plugin action's HookResult, then fold their {@code decision} maps
     * into a single {@link Decision}. Multiple plugins firing on the same event compose by
     * latest-wins for replacement values, any-cancel-wins for cancellation.
     */
    private Decision collectDecisions(String eventName, Map<String, Object> payload) {
        Decision out = new Decision();
        if (timeouts.disabled()) {
            log.debug("Plugin hooks disabled via config; skipping '{}'", eventName);
            return out;
        }
        try {
            List<HookExecutor.HookResult> results =
                    catalog.dispatch(eventName, payload)
                            .timeout(timeouts.preEventCollectDeadline())
                            .onErrorResume(
                                    err -> {
                                        log.warn(
                                                "Plugin hook '{}' dispatch failed (treating as no-op): {}",
                                                eventName,
                                                err.getMessage());
                                        return reactor.core.publisher.Flux.empty();
                                    })
                            .collectList()
                            .block();
            if (results == null || results.isEmpty()) return out;
            for (HookExecutor.HookResult result : results) {
                Map<String, Object> decision = result.decision();
                if (decision == null || decision.isEmpty()) continue;
                out.merge(decision);
            }
        } catch (Exception e) {
            log.warn(
                    "Plugin hook '{}' decision collection failed (treating as no-op): {}",
                    eventName,
                    e.getMessage());
        }
        return out;
    }

    private void fireAndForget(String eventName, Map<String, Object> payload) {
        if (timeouts.disabled()) return;
        try {
            catalog.dispatch(eventName, payload)
                    .timeout(timeouts.postEventBudget())
                    .doOnError(
                            err ->
                                    log.warn(
                                            "Plugin hook '{}' dispatch failed (continuing): {}",
                                            eventName,
                                            err.getMessage()))
                    .onErrorComplete()
                    .subscribe();
        } catch (Exception e) {
            log.warn(
                    "Plugin hook bridge '{}' synchronous error: {}", eventName, e.getMessage());
        }
    }

    /** Folded decision view across multiple plugin hook actions on one event. */
    static final class Decision {
        boolean cancel;
        Map<String, Object> replacementToolInput;
        String replacementPrompt;
        List<String> raisedDeny = new ArrayList<>();

        void merge(Map<String, Object> decision) {
            // Cancellation flags from any plugin win.
            if (Boolean.FALSE.equals(decision.get("continue"))) cancel = true;
            if (Boolean.TRUE.equals(decision.get("block"))) cancel = true;
            Object deny = decision.get("deny");
            if (deny instanceof String s && !s.isBlank()) {
                cancel = true;
                raisedDeny.add(s);
            }
            // Replacement maps / strings — latest plugin wins.
            Object newInput = decision.get("tool_input");
            if (newInput instanceof Map<?, ?> m) {
                Map<String, Object> typed = new HashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    typed.put(String.valueOf(e.getKey()), e.getValue());
                }
                replacementToolInput = typed;
            }
            Object prompt = decision.get("modifiedPrompt");
            if (prompt == null) prompt = decision.get("prompt");
            if (prompt instanceof String s && !s.isBlank()) {
                replacementPrompt = s;
            }
        }
    }
}
