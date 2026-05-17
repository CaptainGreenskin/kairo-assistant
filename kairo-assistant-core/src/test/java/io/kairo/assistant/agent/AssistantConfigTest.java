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
}
