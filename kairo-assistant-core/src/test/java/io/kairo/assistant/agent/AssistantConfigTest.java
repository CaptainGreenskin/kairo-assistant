package io.kairo.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AssistantConfigTest {

    @Test
    void defaultsAreReasonable() {
        AssistantConfig config =
                AssistantConfig.builder().apiKey("test-key").build();

        assertThat(config.modelProvider()).isEqualTo("anthropic");
        assertThat(config.modelName()).isEqualTo("claude-sonnet-4-6");
        assertThat(config.maxIterations()).isEqualTo(30);
        assertThat(config.timeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.tokenBudget()).isEqualTo(128_000);
        assertThat(config.apiKey()).isEqualTo("test-key");
    }

    @Test
    void customValues() {
        AssistantConfig config =
                AssistantConfig.builder()
                        .modelProvider("openai")
                        .modelName("gpt-4o")
                        .apiKey("sk-test")
                        .maxIterations(50)
                        .timeout(Duration.ofMinutes(5))
                        .tokenBudget(64_000)
                        .dataDir("/tmp/test-data")
                        .build();

        assertThat(config.modelProvider()).isEqualTo("openai");
        assertThat(config.modelName()).isEqualTo("gpt-4o");
        assertThat(config.maxIterations()).isEqualTo(50);
        assertThat(config.dataDir()).isEqualTo("/tmp/test-data");
    }

    @Test
    void dataDirDefaultsToUserHome() {
        AssistantConfig config =
                AssistantConfig.builder().apiKey("test-key").build();

        assertThat(config.dataDir())
                .isEqualTo(System.getProperty("user.home") + "/.kairo-assistant");
    }

    @Test
    void apiBaseUrlAutoResolvedForGlm() {
        AssistantConfig config = AssistantConfig.builder()
                .modelProvider("glm").apiKey("glm-key").build();
        assertThat(config.apiBaseUrl()).contains("bigmodel.cn");
    }

    @Test
    void apiBaseUrlAutoResolvedForDeepseek() {
        AssistantConfig config = AssistantConfig.builder()
                .modelProvider("deepseek").apiKey("ds-key").build();
        assertThat(config.apiBaseUrl()).contains("deepseek.com");
    }

    @Test
    void apiBaseUrlNullForAnthropic() {
        AssistantConfig config = AssistantConfig.builder()
                .modelProvider("anthropic").apiKey("ak").build();
        assertThat(config.apiBaseUrl()).isNull();
    }

    @Test
    void envMapDefaultsToEmpty() {
        AssistantConfig config = AssistantConfig.builder().apiKey("k").build();
        assertThat(config.env()).isEmpty();
    }

    @Test
    void envMapIsConfigurable() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("k")
                .env(java.util.Map.of("KEY", "VALUE"))
                .build();
        assertThat(config.env()).containsEntry("KEY", "VALUE");
    }

    @Test
    void timeoutIsConfigurable() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("k")
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
        assertThat(config.timeout()).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    void tokenBudgetIsConfigurable() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("k").tokenBudget(256_000).build();
        assertThat(config.tokenBudget()).isEqualTo(256_000);
    }
}
