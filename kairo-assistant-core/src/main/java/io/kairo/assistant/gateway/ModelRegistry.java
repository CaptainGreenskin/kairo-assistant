package io.kairo.assistant.gateway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ModelRegistry {

    public record ModelSpec(String provider, String modelName, String displayName) {
        public static ModelSpec of(String provider, String modelName) {
            return new ModelSpec(provider, modelName, modelName);
        }

        public static ModelSpec of(String provider, String modelName, String displayName) {
            return new ModelSpec(provider, modelName, displayName);
        }
    }

    private final Map<String, ModelSpec> models = new LinkedHashMap<>();

    public ModelRegistry() {
        registerDefaults();
    }

    public void register(String alias, ModelSpec spec) {
        models.put(alias.toLowerCase(), spec);
    }

    public ModelSpec resolve(String modelId) {
        if (modelId == null) return null;
        String key = modelId.toLowerCase().trim();
        ModelSpec exact = models.get(key);
        if (exact != null) return exact;

        for (var entry : models.entrySet()) {
            if (entry.getValue().modelName().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Set<String> aliases() {
        return Collections.unmodifiableSet(models.keySet());
    }

    public Map<String, ModelSpec> all() {
        return Collections.unmodifiableMap(models);
    }

    private void registerDefaults() {
        register("claude-sonnet", ModelSpec.of("anthropic", "claude-sonnet-4-6", "Claude Sonnet"));
        register("claude-sonnet-4-6", ModelSpec.of("anthropic", "claude-sonnet-4-6", "Claude Sonnet"));
        register("claude-opus", ModelSpec.of("anthropic", "claude-opus-4-6", "Claude Opus"));
        register("claude-opus-4-6", ModelSpec.of("anthropic", "claude-opus-4-6", "Claude Opus"));
        register("claude-haiku", ModelSpec.of("anthropic", "claude-haiku-4-5-20251001", "Claude Haiku"));
        register("gpt-4o", ModelSpec.of("openai", "gpt-4o", "GPT-4o"));
        register("gpt-4o-mini", ModelSpec.of("openai", "gpt-4o-mini", "GPT-4o Mini"));
        register("deepseek-chat", ModelSpec.of("deepseek", "deepseek-chat", "DeepSeek Chat"));
        register("deepseek-reasoner", ModelSpec.of("deepseek", "deepseek-reasoner", "DeepSeek Reasoner"));
        register("glm-4-plus", ModelSpec.of("glm", "glm-4-plus", "GLM-4 Plus"));
        register("glm-4-flash", ModelSpec.of("glm", "glm-4-flash", "GLM-4 Flash"));
        register("qwen-plus", ModelSpec.of("openai", "qwen-plus", "Qwen Plus"));
        register("qwen-turbo", ModelSpec.of("openai", "qwen-turbo", "Qwen Turbo"));
    }
}
