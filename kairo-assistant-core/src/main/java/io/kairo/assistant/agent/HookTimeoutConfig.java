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

import java.time.Duration;

/**
 * Per-action-type timeout policy for plugin hook dispatch.
 *
 * <p>The earlier wiring used a single 15-second cap for every hook event, which made any
 * slow plugin hook stall the parent agent. Real-world hook actions have very different latency
 * profiles:
 *
 * <ul>
 *   <li>{@code command} / {@code http}: usually fast (milliseconds to a few seconds) — keep
 *       short
 *   <li>{@code prompt}: one model call — typically 5-15s
 *   <li>{@code mcp_tool}: depends on the remote tool — give it some headroom
 *   <li>{@code agent}: full sub-agent run with tool loops — allow 60s
 * </ul>
 *
 * <p>{@link #disabled()} returns a "kill-switch" config (zero timeouts) — invoke when an operator
 * needs to fully bypass plugin hooks without uninstalling plugins. Read from environment via
 * {@link #fromEnvironment()}: each {@code KAIRO_HOOK_TIMEOUT_*} variable overrides one tier;
 * {@code KAIRO_HOOK_DISABLED=true} flips the kill switch.
 */
public record HookTimeoutConfig(
        boolean disabled,
        Duration command,
        Duration http,
        Duration prompt,
        Duration agent,
        Duration mcpTool,
        Duration postEventBudget) {

    public HookTimeoutConfig {
        if (command == null) command = Duration.ofSeconds(10);
        if (http == null) http = Duration.ofSeconds(10);
        if (prompt == null) prompt = Duration.ofSeconds(15);
        if (agent == null) agent = Duration.ofSeconds(60);
        if (mcpTool == null) mcpTool = Duration.ofSeconds(30);
        if (postEventBudget == null) postEventBudget = Duration.ofSeconds(15);
    }

    /** Production defaults — balanced for the common Claude Code plugin shapes. */
    public static HookTimeoutConfig defaults() {
        return new HookTimeoutConfig(false, null, null, null, null, null, null);
    }

    /** Kill switch — every hook call short-circuits to a no-op. */
    public static HookTimeoutConfig killSwitch() {
        return new HookTimeoutConfig(
                true,
                Duration.ZERO,
                Duration.ZERO,
                Duration.ZERO,
                Duration.ZERO,
                Duration.ZERO,
                Duration.ZERO);
    }

    /**
     * Build from environment / system properties. Recognised keys (env-style names take
     * precedence, system property names with dots are also honoured):
     *
     * <ul>
     *   <li>{@code KAIRO_HOOK_DISABLED} / {@code kairo.hook.disabled} → kill switch
     *   <li>{@code KAIRO_HOOK_TIMEOUT_COMMAND} / {@code kairo.hook.timeout.command} → seconds
     *   <li>{@code KAIRO_HOOK_TIMEOUT_HTTP} → seconds
     *   <li>{@code KAIRO_HOOK_TIMEOUT_PROMPT} → seconds
     *   <li>{@code KAIRO_HOOK_TIMEOUT_AGENT} → seconds
     *   <li>{@code KAIRO_HOOK_TIMEOUT_MCP_TOOL} → seconds
     *   <li>{@code KAIRO_HOOK_TIMEOUT_POST_EVENT} → seconds (fire-and-forget cap)
     * </ul>
     */
    public static HookTimeoutConfig fromEnvironment() {
        if (boolFromEnv("KAIRO_HOOK_DISABLED", "kairo.hook.disabled", false)) {
            return killSwitch();
        }
        Duration defaultsCommand = Duration.ofSeconds(10);
        Duration defaultsHttp = Duration.ofSeconds(10);
        Duration defaultsPrompt = Duration.ofSeconds(15);
        Duration defaultsAgent = Duration.ofSeconds(60);
        Duration defaultsMcp = Duration.ofSeconds(30);
        Duration defaultsPost = Duration.ofSeconds(15);
        return new HookTimeoutConfig(
                false,
                secondsFromEnv("KAIRO_HOOK_TIMEOUT_COMMAND", "kairo.hook.timeout.command", defaultsCommand),
                secondsFromEnv("KAIRO_HOOK_TIMEOUT_HTTP", "kairo.hook.timeout.http", defaultsHttp),
                secondsFromEnv("KAIRO_HOOK_TIMEOUT_PROMPT", "kairo.hook.timeout.prompt", defaultsPrompt),
                secondsFromEnv("KAIRO_HOOK_TIMEOUT_AGENT", "kairo.hook.timeout.agent", defaultsAgent),
                secondsFromEnv("KAIRO_HOOK_TIMEOUT_MCP_TOOL", "kairo.hook.timeout.mcp_tool", defaultsMcp),
                secondsFromEnv("KAIRO_HOOK_TIMEOUT_POST_EVENT", "kairo.hook.timeout.post_event", defaultsPost));
    }

    /** Pick the right per-action timeout. */
    public Duration timeoutForActionType(String type) {
        if (disabled) return Duration.ZERO;
        if (type == null) return prompt;
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "command" -> command;
            case "http" -> http;
            case "prompt" -> prompt;
            case "agent" -> agent;
            case "mcp_tool" -> mcpTool;
            default -> prompt;
        };
    }

    /** Maximum aggregate Pre*-event collect-decision deadline (worst-case across actions). */
    public Duration preEventCollectDeadline() {
        if (disabled) return Duration.ZERO;
        // Take the largest individual action timeout so the slowest action can still complete.
        Duration max = command;
        if (http.compareTo(max) > 0) max = http;
        if (prompt.compareTo(max) > 0) max = prompt;
        if (agent.compareTo(max) > 0) max = agent;
        if (mcpTool.compareTo(max) > 0) max = mcpTool;
        return max;
    }

    private static boolean boolFromEnv(String env, String prop, boolean fallback) {
        String v = System.getenv(env);
        if (v == null) v = System.getProperty(prop);
        if (v == null || v.isBlank()) return fallback;
        return Boolean.parseBoolean(v.trim());
    }

    private static Duration secondsFromEnv(String env, String prop, Duration fallback) {
        String v = System.getenv(env);
        if (v == null) v = System.getProperty(prop);
        if (v == null || v.isBlank()) return fallback;
        try {
            long s = Long.parseLong(v.trim());
            return s <= 0 ? Duration.ZERO : Duration.ofSeconds(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
