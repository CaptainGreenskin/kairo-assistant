package io.kairo.assistant.agent;

import java.time.Duration;
import java.util.Map;

public record AssistantConfig(
        String modelProvider,
        String modelName,
        String apiKey,
        String apiBaseUrl,
        int maxIterations,
        Duration timeout,
        int tokenBudget,
        String dataDir,
        int sessionPoolSize,
        Duration sessionIdleTtl,
        float compactionTrigger,
        Map<String, String> env) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String modelProvider = "anthropic";
        private String modelName = "claude-sonnet-4-6";
        private String apiKey;
        private String apiBaseUrl;
        private int maxIterations = 10;
        private Duration timeout = Duration.ofMinutes(10);
        private int tokenBudget = 128_000;
        private String dataDir = System.getProperty("user.home") + "/.kairo-assistant";
        private int sessionPoolSize = 64;
        private Duration sessionIdleTtl = Duration.ofMinutes(60);
        private float compactionTrigger = 0.50f;
        private Map<String, String> env = Map.of();

        public Builder modelProvider(String v) {
            this.modelProvider = v;
            return this;
        }

        public Builder modelName(String v) {
            this.modelName = v;
            return this;
        }

        public Builder apiKey(String v) {
            this.apiKey = v;
            return this;
        }

        public Builder apiBaseUrl(String v) {
            this.apiBaseUrl = v;
            return this;
        }

        public Builder maxIterations(int v) {
            this.maxIterations = v;
            return this;
        }

        public Builder timeout(Duration v) {
            this.timeout = v;
            return this;
        }

        public Builder tokenBudget(int v) {
            this.tokenBudget = v;
            return this;
        }

        public Builder dataDir(String v) {
            this.dataDir = v;
            return this;
        }

        public Builder sessionPoolSize(int v) {
            this.sessionPoolSize = v;
            return this;
        }

        public Builder sessionIdleTtl(Duration v) {
            this.sessionIdleTtl = v;
            return this;
        }

        public Builder compactionTrigger(float v) {
            this.compactionTrigger = v;
            return this;
        }

        public Builder env(Map<String, String> v) {
            this.env = v;
            return this;
        }

        public AssistantConfig build() {
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = resolveApiKey(modelProvider);
            }
            if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
                apiBaseUrl = resolveBaseUrl(modelProvider);
            }
            return new AssistantConfig(
                    modelProvider,
                    modelName,
                    apiKey,
                    apiBaseUrl,
                    maxIterations,
                    timeout,
                    tokenBudget,
                    dataDir,
                    sessionPoolSize,
                    sessionIdleTtl,
                    compactionTrigger,
                    env);
        }

        /**
         * Resolve the API key for {@code provider} from environment variables. Tries the universal
         * {@code KAIRO_API_KEY} first, then the conventional {@code <PROVIDER>_API_KEY} (with the
         * Gemini alias to {@code GOOGLE_API_KEY}), then the legacy {@code KAIRO_ASSISTANT_API_KEY}
         * fallback.
         *
         * <p>The set of acceptable provider names is now the union of what {@link
         * io.kairo.core.model.DefaultProviderRegistry#withBuiltIns()} knows about (anthropic /
         * openai / gemini / qwen / glm / deepseek / minimax / kimi / moonshot / groq / xai / grok
         * / openrouter / ollama / lm-studio / openai-compatible). No hard-coded switch here.
         */
        private String resolveApiKey(String provider) {
            String universal = System.getenv("KAIRO_API_KEY");
            if (universal != null && !universal.isBlank()) {
                return universal;
            }
            if (provider != null && !provider.isBlank()) {
                String envName =
                        ("gemini".equalsIgnoreCase(provider) || "google".equalsIgnoreCase(provider))
                                ? "GOOGLE_API_KEY"
                                : provider.replace('-', '_').toUpperCase() + "_API_KEY";
                String fromProviderVar = System.getenv(envName);
                if (fromProviderVar != null && !fromProviderVar.isBlank()) {
                    return fromProviderVar;
                }
            }
            return System.getenv("KAIRO_ASSISTANT_API_KEY");
        }

        /**
         * Resolve the base URL override for {@code provider}. The {@code KAIRO_BASE_URL} env var
         * always wins. Otherwise we return {@code null} and let {@link
         * io.kairo.core.model.DefaultProviderRegistry} apply its built-in preset URL — no
         * duplicate per-provider switch needed here.
         */
        private String resolveBaseUrl(String provider) {
            String envUrl = System.getenv("KAIRO_BASE_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                return envUrl;
            }
            return null;
        }
    }
}
