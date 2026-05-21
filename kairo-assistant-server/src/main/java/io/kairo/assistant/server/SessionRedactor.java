/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.server;

import java.util.regex.Pattern;

/**
 * Pattern-based scrubber for sensitive substrings before a session leaves the box. Mirrors the
 * Hermes Replay "Safe Share Mode" posture — we scrub before emit, even when the user is exporting
 * to a local file (because users routinely paste local files into screenshots and PRs).
 *
 * <p>Categories covered (each replaced with a typed token so reviewers can see what kind of data
 * was scrubbed):
 *
 * <ul>
 *   <li>API keys — typical sk-/pk-/api- prefixes + 20+ chars of base64-ish
 *   <li>OpenAI / Anthropic style bearer tokens
 *   <li>JWTs (three base64 segments split by .)
 *   <li>Emails
 *   <li>Absolute filesystem paths (POSIX + Windows)
 *   <li>UUIDs (preserved length so downstream parsers don't choke)
 * </ul>
 *
 * <p>Production deployments should plug in {@code kairo-security-pii}'s full PII detector for a
 * proper NER pass — this guard catches the obvious cases.
 */
public final class SessionRedactor {

    private record Rule(Pattern pattern, String replacement) {}

    private static final Rule[] RULES = {
        // API keys — sk-..., pk-..., anthropic-ant-..., gh_pat_..., etc.
        new Rule(
                Pattern.compile(
                        "\\b(?:sk|pk|api|gh[ops]?|ghp|ghs|ghr|ant|xoxb|xoxp|aws|asia|akia)[_-][A-Za-z0-9_\\-]{20,}\\b"),
                "[REDACTED_KEY]"),
        // OpenAI/Anthropic bearer tokens often appear as "Bearer sk-..."
        new Rule(Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{20,}"), "[REDACTED_BEARER]"),
        // JWT — three base64 chunks separated by dots, common minimum total length
        new Rule(
                Pattern.compile("\\b[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\b"),
                "[REDACTED_JWT]"),
        // Emails
        new Rule(
                Pattern.compile(
                        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
                "[REDACTED_EMAIL]"),
        // Absolute paths (POSIX) — /Users/..., /home/..., /var/..., /opt/..., /etc/...
        new Rule(
                Pattern.compile(
                        "\\b(?:/Users/|/home/|/var/|/opt/|/etc/|/tmp/|/srv/)[A-Za-z0-9._/\\-]+"),
                "[REDACTED_PATH]"),
        // Absolute paths (Windows)
        new Rule(
                Pattern.compile(
                        "\\b[A-Za-z]:\\\\(?:[A-Za-z0-9._\\-]+\\\\?)+"),
                "[REDACTED_PATH]"),
        // UUIDs — preserved-length so structured logs don't break
        new Rule(
                Pattern.compile(
                        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"),
                "[REDACTED_UUID]"),
    };

    private SessionRedactor() {}

    /** Apply every redaction rule to {@code input}. Returns the scrubbed string. */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input;
        String working = input;
        for (Rule rule : RULES) {
            working = rule.pattern.matcher(working).replaceAll(rule.replacement);
        }
        return working;
    }
}
