package io.kairo.assistant.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthControllerTest {

    private HealthController controller;

    @BeforeEach
    void setUp() {
        controller = new HealthController(TestFixtures.defaultSession(), TestFixtures.stubGateway());
    }

    @Test
    void healthReturnsOk() {
        var result = controller.health();
        assertThat(result.get("status")).isEqualTo("ok");
    }

    @Test
    void healthV1ReturnsOk() {
        var result = controller.healthV1();
        assertThat(result.get("status")).isEqualTo("ok");
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthDetailedContainsExpectedFields() {
        var result = controller.healthDetailed();
        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result).containsKey("uptime");
        assertThat(result).containsKey("uptimeSeconds");
        assertThat(result).containsKey("memory");
        assertThat(result).containsKey("sessionPool");
        assertThat(result).containsKey("model");
        assertThat(result).containsKey("jvm");

        var mem = (Map<String, Object>) result.get("memory");
        assertThat(mem).containsKeys("heapUsedMb", "heapMaxMb", "heapUsagePercent");

        var pool = (Map<String, Object>) result.get("sessionPool");
        assertThat(pool.get("activeSessions")).isEqualTo(0);
        assertThat(pool.get("maxSessions")).isEqualTo(64);

        var model = (Map<String, Object>) result.get("model");
        assertThat(model.get("provider")).isEqualTo("anthropic");
        assertThat(model.get("name")).isEqualTo("claude-test");
    }
}
