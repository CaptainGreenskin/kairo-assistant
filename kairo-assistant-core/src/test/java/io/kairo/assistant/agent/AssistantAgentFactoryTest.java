package io.kairo.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssistantAgentFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void createRejectsNullConfig() {
        assertThatThrownBy(() -> AssistantAgentFactory.create(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void createBuildsFullSessionWithAnthropicProvider() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .modelProvider("anthropic")
                .dataDir(tempDir.toString())
                .build();

        AssistantSession session = AssistantAgentFactory.create(config);

        assertThat(session.agent()).isNotNull();
        assertThat(session.agent().name()).isEqualTo("kairo-assistant");
        assertThat(session.toolRegistry()).isNotNull();
        assertThat(session.toolExecutor()).isNotNull();
        assertThat(session.memoryStore()).isNotNull();
        assertThat(session.cronScheduler()).isNotNull();
        assertThat(session.skillRegistry()).isNotNull();
        assertThat(session.pluginManager()).isNotNull();
        assertThat(session.config()).isSameAs(config);
    }

    @Test
    void createBuildsSessionWithOpenaiProvider() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .modelProvider("openai")
                .dataDir(tempDir.toString())
                .build();

        AssistantSession session = AssistantAgentFactory.create(config);

        assertThat(session.agent()).isNotNull();
        assertThat(session.agent().name()).isEqualTo("kairo-assistant");
    }

    @Test
    void createBuildsSessionWithGlmProvider() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .modelProvider("glm")
                .apiBaseUrl("https://open.bigmodel.cn/api/paas/v4")
                .dataDir(tempDir.toString())
                .build();

        AssistantSession session = AssistantAgentFactory.create(config);

        assertThat(session.agent()).isNotNull();
    }

    @Test
    void createCreatesDataDirectory() {
        Path dataDir = tempDir.resolve("new-dir");
        assertThat(dataDir).doesNotExist();

        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .dataDir(dataDir.toString())
                .build();

        AssistantAgentFactory.create(config);

        assertThat(dataDir).exists().isDirectory();
    }

    @Test
    void createLoadsCustomInstructions() throws IOException {
        Files.writeString(tempDir.resolve("custom-instructions.md"), "Always respond in JSON");

        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .dataDir(tempDir.toString())
                .build();

        AssistantSession session = AssistantAgentFactory.create(config);

        assertThat(session.agent()).isNotNull();
    }

    @Test
    void createRegistersTools() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .dataDir(tempDir.toString())
                .build();

        AssistantSession session = AssistantAgentFactory.create(config);

        assertThat(session.toolRegistry().getAll()).isNotEmpty();
    }

    @Test
    void createRegistersSkills() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .dataDir(tempDir.toString())
                .build();

        AssistantSession session = AssistantAgentFactory.create(config);

        assertThat(session.skillRegistry().list()).isNotEmpty();
    }

    @Test
    void sessionStartAndStopDoNotThrow() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .dataDir(tempDir.toString())
                .build();

        AssistantSession session = AssistantAgentFactory.create(config);

        session.start();
        session.stop();
    }

    @Test
    void unknownProviderFallsBackToOpenai() {
        AssistantConfig config = AssistantConfig.builder()
                .apiKey("test-key")
                .modelProvider("some-unknown-provider")
                .dataDir(tempDir.toString())
                .build();

        AssistantSession session = AssistantAgentFactory.create(config);

        assertThat(session.agent()).isNotNull();
    }
}
