package io.kairo.assistant.gateway;

import io.kairo.core.session.UnifiedGateway;
import io.kairo.core.session.SessionKey;

import io.kairo.api.agent.Agent;
import io.kairo.assistant.agent.AssistantAgentFactory;
import io.kairo.assistant.agent.AssistantConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelSwitchService {

    private static final Logger log = LoggerFactory.getLogger(ModelSwitchService.class);

    private final UnifiedGateway gateway;
    private final ModelRegistry registry;
    private final AssistantConfig baseConfig;

    public ModelSwitchService(UnifiedGateway gateway, ModelRegistry registry,
                              AssistantConfig baseConfig) {
        this.gateway = gateway;
        this.registry = registry;
        this.baseConfig = baseConfig;
    }

    public SwitchResult switchModel(SessionKey key, String modelId) {
        ModelRegistry.ModelSpec spec = registry.resolve(modelId);
        if (spec == null) {
            return SwitchResult.unknownModel(modelId, registry.aliases());
        }

        try {
            Agent newAgent = AssistantAgentFactory.createAgentWithModel(
                    baseConfig, spec.provider(), spec.modelName());
            gateway.switchModel(key, newAgent);
            log.info("Switched session [{}] to model {} ({})",
                    key, spec.modelName(), spec.provider());
            return SwitchResult.success(spec);
        } catch (Exception e) {
            log.error("Failed to switch model for session [{}]: {}", key, e.getMessage(), e);
            return SwitchResult.error(e.getMessage());
        }
    }

    public ModelRegistry registry() {
        return registry;
    }

    public record SwitchResult(boolean success, String message, ModelRegistry.ModelSpec spec) {
        public static SwitchResult success(ModelRegistry.ModelSpec spec) {
            return new SwitchResult(true,
                    "Switched to " + spec.displayName() + " (" + spec.modelName() + ")", spec);
        }

        public static SwitchResult unknownModel(String modelId, java.util.Set<String> available) {
            return new SwitchResult(false,
                    "Unknown model: " + modelId + ". Available: " + String.join(", ", available),
                    null);
        }

        public static SwitchResult error(String reason) {
            return new SwitchResult(false, "Model switch failed: " + reason, null);
        }
    }
}
