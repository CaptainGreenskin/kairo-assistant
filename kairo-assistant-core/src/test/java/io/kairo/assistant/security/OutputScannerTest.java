package io.kairo.assistant.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutputScannerTest {

    private final OutputScanner scanner = new OutputScanner(true);

    @Test
    void cleanContentPasses() {
        OutputScanner.ScanResult result = scanner.scan("Hello world, nothing sensitive here.");
        assertThat(result.isClean()).isTrue();
    }

    @Test
    void detectsAwsKey() {
        OutputScanner.ScanResult result = scanner.scan("Found key: AKIAIOSFODNN7EXAMPLE");
        assertThat(result.isClean()).isFalse();
        assertThat(result.findings()).anyMatch(f -> f.ruleId().equals("AWS_KEY"));
    }

    @Test
    void detectsPrivateKey() {
        OutputScanner.ScanResult result = scanner.scan("-----BEGIN RSA PRIVATE KEY-----\nMIIE...");
        assertThat(result.isClean()).isFalse();
        assertThat(result.findings()).anyMatch(f -> f.ruleId().equals("PRIVATE_KEY"));
    }

    @Test
    void detectsGithubToken() {
        OutputScanner.ScanResult result = scanner.scan("token=ghp_ABCDefgh1234567890abcdefgh1234567890");
        assertThat(result.isClean()).isFalse();
        assertThat(result.findings()).anyMatch(f -> f.ruleId().equals("GITHUB_TOKEN"));
    }

    @Test
    void detectsChinesePhone() {
        OutputScanner.ScanResult result = scanner.scan("Contact: 13812345678");
        assertThat(result.isClean()).isFalse();
        assertThat(result.findings()).anyMatch(f -> f.ruleId().equals("PHONE_CN"));
    }

    @Test
    void detectsEmail() {
        OutputScanner.ScanResult result = scanner.scan("Send to user@example.com please");
        assertThat(result.isClean()).isFalse();
        assertThat(result.findings()).anyMatch(f -> f.ruleId().equals("EMAIL_PII"));
    }

    @Test
    void redactsSecrets() {
        String input = "Key: AKIAIOSFODNN7EXAMPLE and phone 13812345678";
        String redacted = scanner.redact(input);
        assertThat(redacted).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(redacted).doesNotContain("13812345678");
        assertThat(redacted).contains("[REDACTED:");
    }

    @Test
    void disabledScannerAlwaysClean() {
        OutputScanner disabled = new OutputScanner(false);
        OutputScanner.ScanResult result = disabled.scan("AKIAIOSFODNN7EXAMPLE");
        assertThat(result.isClean()).isTrue();
    }

    @Test
    void disabledScannerDoesNotRedact() {
        OutputScanner disabled = new OutputScanner(false);
        String input = "AKIAIOSFODNN7EXAMPLE";
        assertThat(disabled.redact(input)).isEqualTo(input);
    }

    @Test
    void nullOrBlankIsClean() {
        assertThat(scanner.scan(null).isClean()).isTrue();
        assertThat(scanner.scan("").isClean()).isTrue();
        assertThat(scanner.scan("   ").isClean()).isTrue();
    }
}
