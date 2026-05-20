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

import io.kairo.api.cron.CronFireCallback;
import io.kairo.api.cron.CronTask;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CronFireCallback} decorator that screens the task's prompt for prompt-injection markers
 * <em>before</em> handing off to the underlying callback. Hermes ships a more sophisticated
 * scanner; this is the minimum-viable equivalent — pattern-based detection of:
 *
 * <ul>
 *   <li>Classic jailbreak phrases ("ignore previous instructions", "you are now in DAN mode", …)
 *   <li>Markdown-injection that pulls in remote system prompts ({@code @http(...)},
 *       {@code @include(...)})
 *   <li>Role-confusion attempts ({@code system:}, {@code <|im_start|>} etc. at line start)
 * </ul>
 *
 * <p>When {@link Policy#ABORT} is configured, hits cause the task to no-op (logged WARN). When
 * {@link Policy#REDACT}, matches are replaced with {@code [REDACTED]} and the cleaned task is
 * passed downstream. Set policy via {@code KAIRO_CRON_INJECTION_POLICY=abort|redact|off}.
 *
 * <p>Production deployments should override the wrapped {@link CronFireCallback} bean to plug in
 * kairo-security-pii's full scanner — this guard only catches the obvious cases.
 */
public final class PromptInjectionGuard implements CronFireCallback {

    public enum Policy {
        OFF,
        REDACT,
        ABORT;

        static Policy fromEnv() {
            String raw = System.getenv("KAIRO_CRON_INJECTION_POLICY");
            if (raw == null || raw.isBlank()) {
                raw = System.getProperty("kairo.cron.injection.policy", "redact");
            }
            try {
                return Policy.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return REDACT;
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionGuard.class);

    private static final List<Pattern> JAILBREAK_PATTERNS =
            List.of(
                    Pattern.compile(
                            "(?i)ignore\\s+(?:all|previous|prior|the\\s+above)\\s+instructions"),
                    Pattern.compile("(?i)you\\s+are\\s+(?:now\\s+)?(?:in\\s+)?DAN(?:\\s+mode)?"),
                    Pattern.compile("(?i)disregard\\s+(?:all|previous|prior)\\s+rules"),
                    Pattern.compile("(?i)pretend\\s+to\\s+be\\s+(?:a\\s+)?(?:different|new)\\s+(?:assistant|AI|model)"),
                    Pattern.compile("(?im)^\\s*(?:system|assistant|user)\\s*:"),
                    Pattern.compile("<\\|im_start\\|>"),
                    Pattern.compile("@(?:http|include|fetch)\\s*\\([^)]+\\)"));

    private final CronFireCallback delegate;
    private final Policy policy;

    public PromptInjectionGuard(CronFireCallback delegate) {
        this(delegate, Policy.fromEnv());
    }

    public PromptInjectionGuard(CronFireCallback delegate, Policy policy) {
        this.delegate = delegate;
        this.policy = policy == null ? Policy.REDACT : policy;
    }

    public Policy policy() {
        return policy;
    }

    @Override
    public void onFire(CronTask task) {
        if (policy == Policy.OFF) {
            delegate.onFire(task);
            return;
        }
        ScanResult scan = scan(task.prompt());
        if (!scan.hit()) {
            delegate.onFire(task);
            return;
        }
        if (policy == Policy.ABORT) {
            log.warn(
                    "Cron task {} aborted by injection guard: {} pattern(s) matched",
                    task.id(),
                    scan.matchCount());
            return;
        }
        // REDACT — rebuild task with redacted prompt and forward.
        log.warn(
                "Cron task {} prompt redacted by injection guard: {} pattern(s) matched",
                task.id(),
                scan.matchCount());
        CronTask cleaned = task.withCronAndPrompt(null, scan.cleaned());
        delegate.onFire(cleaned);
    }

    /** Visible for testing — apply every pattern, return cleaned text + hit count. */
    static ScanResult scan(String prompt) {
        if (prompt == null || prompt.isEmpty()) return new ScanResult("", 0);
        String working = prompt;
        int hits = 0;
        for (Pattern p : JAILBREAK_PATTERNS) {
            var matcher = p.matcher(working);
            int local = 0;
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("[REDACTED]"));
                local++;
            }
            matcher.appendTail(out);
            if (local > 0) {
                hits += local;
                working = out.toString();
            }
        }
        return new ScanResult(working, hits);
    }

    /** Test-visible scan summary. */
    record ScanResult(String cleaned, int matchCount) {
        boolean hit() {
            return matchCount > 0;
        }
    }
}
