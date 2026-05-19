package io.kairo.assistant.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OutputScanner {

    private static final Logger log = LoggerFactory.getLogger(OutputScanner.class);

    private static final List<ScanRule> DEFAULT_RULES = List.of(
            new ScanRule("AWS_KEY", Pattern.compile("AKIA[0-9A-Z]{16}"), "AWS Access Key"),
            new ScanRule("AWS_SECRET", Pattern.compile("(?i)aws[_\\-]?secret[_\\-]?access[_\\-]?key\\s*[=:]\\s*[A-Za-z0-9/+=]{40}"), "AWS Secret Key"),
            new ScanRule("GENERIC_SECRET", Pattern.compile("(?i)(password|passwd|pwd|secret|token|api[_-]?key)\\s*[=:]\\s*['\"]?[A-Za-z0-9!@#$%^&*()_+\\-=]{8,}"), "Generic Secret"),
            new ScanRule("PRIVATE_KEY", Pattern.compile("-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----"), "Private Key"),
            new ScanRule("GITHUB_TOKEN", Pattern.compile("gh[pousr]_[A-Za-z0-9_]{36,255}"), "GitHub Token"),
            new ScanRule("SLACK_TOKEN", Pattern.compile("xox[baprs]-[0-9]{10,13}-[0-9]{10,13}-[a-zA-Z0-9]{24,32}"), "Slack Token"),
            new ScanRule("EMAIL_PII", Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}"), "Email Address"),
            new ScanRule("PHONE_CN", Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "Chinese Phone Number"),
            new ScanRule("ID_CARD_CN", Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)"), "Chinese ID Card")
    );

    private final List<ScanRule> rules;
    private final boolean enabled;

    public OutputScanner(boolean enabled) {
        this(enabled, DEFAULT_RULES);
    }

    public OutputScanner(boolean enabled, List<ScanRule> rules) {
        this.enabled = enabled;
        this.rules = rules;
    }

    public ScanResult scan(String content) {
        if (!enabled || content == null || content.isBlank()) {
            return ScanResult.CLEAN;
        }

        List<Finding> findings = new ArrayList<>();
        for (ScanRule rule : rules) {
            if (rule.pattern().matcher(content).find()) {
                findings.add(new Finding(rule.id(), rule.description()));
            }
        }

        if (findings.isEmpty()) {
            return ScanResult.CLEAN;
        }

        log.warn("Security scan found {} issues in output", findings.size());
        return new ScanResult(findings);
    }

    public String redact(String content) {
        if (!enabled || content == null) return content;

        String result = content;
        for (ScanRule rule : rules) {
            result = rule.pattern().matcher(result)
                    .replaceAll("[REDACTED:" + rule.id() + "]");
        }
        return result;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public record ScanRule(String id, Pattern pattern, String description) {}

    public record Finding(String ruleId, String description) {}

    public record ScanResult(List<Finding> findings) {
        public static final ScanResult CLEAN = new ScanResult(List.of());

        public boolean isClean() {
            return findings.isEmpty();
        }

        public int count() {
            return findings.size();
        }
    }
}
