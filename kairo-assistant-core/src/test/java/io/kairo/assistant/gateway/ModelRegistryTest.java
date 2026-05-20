package io.kairo.assistant.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelRegistryTest {

    private final ModelRegistry registry = new ModelRegistry();

    @Test
    void resolvesByAlias() {
        var spec = registry.resolve("claude-opus");
        assertThat(spec).isNotNull();
        assertThat(spec.provider()).isEqualTo("anthropic");
        assertThat(spec.modelName()).isEqualTo("claude-opus-4-6");
    }

    @Test
    void resolvesByModelName() {
        var spec = registry.resolve("deepseek-chat");
        assertThat(spec).isNotNull();
        assertThat(spec.provider()).isEqualTo("deepseek");
    }

    @Test
    void resolveIsCaseInsensitive() {
        var spec = registry.resolve("Claude-Sonnet");
        assertThat(spec).isNotNull();
        assertThat(spec.modelName()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void resolveReturnsNullForUnknown() {
        assertThat(registry.resolve("nonexistent-model")).isNull();
    }

    @Test
    void customRegistration() {
        registry.register("my-model", ModelRegistry.ModelSpec.of("openai", "ft:gpt-4o:custom"));
        var spec = registry.resolve("my-model");
        assertThat(spec).isNotNull();
        assertThat(spec.modelName()).isEqualTo("ft:gpt-4o:custom");
    }

    @Test
    void aliasesContainsDefaults() {
        assertThat(registry.aliases())
                .contains("claude-sonnet", "claude-opus", "gpt-4o", "deepseek-chat", "glm-4-plus");
    }

    @Test
    void allReturnsUnmodifiableMap() {
        var all = registry.all();
        assertThat(all).isNotEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> all.put("test", ModelRegistry.ModelSpec.of("x", "y")));
    }
}
