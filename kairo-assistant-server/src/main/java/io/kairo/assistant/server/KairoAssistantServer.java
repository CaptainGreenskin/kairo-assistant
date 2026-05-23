package io.kairo.assistant.server;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.assistant.agent.AssistantAgentFactory;
import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.ConversationStore;
import io.kairo.core.session.AgentSessionPool;
import io.kairo.assistant.gateway.ModelRegistry;
import io.kairo.assistant.gateway.ModelSwitchService;
import io.kairo.core.session.SessionKey;
import io.kairo.core.session.UnifiedGateway;
import io.kairo.assistant.goal.Goal;
import io.kairo.assistant.goal.GoalScheduler;
import io.kairo.assistant.goal.GoalStore;
import io.kairo.assistant.security.OutputScanner;
import io.kairo.assistant.security.UserPairing;
import io.kairo.assistant.tool.GoalTool;
import io.kairo.core.agent.DefaultReActAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class KairoAssistantServer {

    private static final Logger log = LoggerFactory.getLogger(KairoAssistantServer.class);

    @Bean
    public AssistantConfig assistantConfig() {
        return AssistantConfig.builder()
                .modelProvider(env("KAIRO_PROVIDER", "anthropic"))
                .modelName(env("KAIRO_MODEL", "claude-sonnet-4-6"))
                .sessionPoolSize(safeParseInt(
                        System.getenv().getOrDefault("KAIRO_SESSION_POOL_SIZE", "64"), 64))
                .sessionIdleTtl(Duration.ofMinutes(safeParseInt(
                        System.getenv().getOrDefault("KAIRO_SESSION_IDLE_TTL_MINUTES", "60"), 60)))
                .compactionTrigger(safeParseFloat(
                        System.getenv().getOrDefault("KAIRO_COMPACTION_TRIGGER", "0.50"), 0.50f))
                .build();
    }

    @Bean
    public AssistantSession assistantSession(AssistantConfig config) {
        AssistantSession session = AssistantAgentFactory.create(config);
        session.start();
        restoreConversationHistory(session);
        return session;
    }

    @Bean
    public UnifiedGateway unifiedGateway(AssistantConfig config,
                                         SessionAwareDeltaRouter deltaRouter) {
        // Per-key agent factory: wire a session-scoped delta consumer so each
        // pooled agent's streaming tokens flow into the right SSE/WS channel.
        // Without this the chat SSE saw zero `delta` events and the UI sat
        // idle until the model finished — see streaming verification in the
        // audit notes.
        AgentSessionPool pool = new AgentSessionPool(
                config.sessionPoolSize(),
                config.sessionIdleTtl(),
                key -> AssistantAgentFactory.createPooledAgent(
                        config, deltaRouter.consumerFor(key)),
                evictedKey -> deltaRouter.removeSession(evictedKey));
        int maxConcurrent = safeParseInt(
                System.getenv().getOrDefault("KAIRO_MAX_CONCURRENT_RUNS", "16"), 16);
        return new UnifiedGateway(pool, maxConcurrent);
    }

    @Bean
    public ModelRegistry modelRegistry() {
        return new ModelRegistry();
    }

    @Bean
    public ModelSwitchService modelSwitchService(UnifiedGateway gateway,
                                                  ModelRegistry registry,
                                                  AssistantConfig config) {
        return new ModelSwitchService(gateway, registry, config);
    }

    @Bean
    public ConversationStore conversationStore(AssistantSession session) {
        Path dataDir = Path.of(session.config().dataDir());
        return new ConversationStore(dataDir.resolve("conversations"));
    }

    @Bean
    public OutboundMessageRouter outboundMessageRouter() {
        return new OutboundMessageRouter();
    }

    @Bean
    public GoalStore goalStore(AssistantSession session) {
        Path dataDir = Path.of(session.config().dataDir()).resolve("goals");
        GoalStore store = new GoalStore(dataDir);
        GoalTool.setStore(store);
        return store;
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public GoalScheduler goalScheduler(GoalStore store, UnifiedGateway gateway,
                                       OutboundMessageRouter outboundRouter) {
        ZoneId zone = ZoneId.of(env("KAIRO_TIMEZONE", "Asia/Shanghai"));
        return new GoalScheduler(store, (goal, prompt) -> {
            try {
                SessionKey key = SessionKey.of("goal", goal.id());
                Msg input = Msg.of(MsgRole.USER, prompt);
                Msg result = gateway.route(key, input).block(Duration.ofMinutes(5));
                if (result != null && goal.channel() != null && !goal.channel().isBlank()) {
                    String dest = goal.target() != null && !goal.target().isBlank()
                            ? goal.target() : "default";
                    outboundRouter.send(goal.channel(), dest,
                            "[Goal: " + goal.description() + "]\n" + result.text());
                }
                log.info("Goal [{}] executed successfully", goal.id());
            } catch (Exception e) {
                log.error("Goal [{}] execution failed: {}", goal.id(), e.getMessage());
            }
        }, zone);
    }

    @Bean
    public UserPairing userPairing(AssistantSession session) {
        Path dataDir = Path.of(session.config().dataDir());
        boolean enabled = Boolean.parseBoolean(
                System.getenv().getOrDefault("KAIRO_PAIRING_ENABLED", "false"));
        return new UserPairing(dataDir, enabled);
    }

    @Bean
    public OutputScanner outputScanner() {
        boolean enabled = Boolean.parseBoolean(
                System.getenv().getOrDefault("KAIRO_SECURITY_SCAN", "true"));
        return new OutputScanner(enabled);
    }

    @Bean
    public SessionManager sessionManager(AssistantSession session) {
        return new SessionManager(session);
    }

    private static final int MAX_RESTORE_MESSAGES = safeParseInt(
            System.getenv().getOrDefault("KAIRO_MAX_RESTORE_MESSAGES", "50"), 50);

    private static int safeParseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LoggerFactory.getLogger(KairoAssistantServer.class)
                    .warn("Invalid integer '{}', using default {}", value, defaultValue);
            return defaultValue;
        }
    }

    private static float safeParseFloat(String value, float defaultValue) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            LoggerFactory.getLogger(KairoAssistantServer.class)
                    .warn("Invalid float '{}', using default {}", value, defaultValue);
            return defaultValue;
        }
    }

    private void restoreConversationHistory(AssistantSession session) {
        if (!(session.agent() instanceof DefaultReActAgent agent)) return;

        Path dataDir = Path.of(session.config().dataDir()).resolve("conversations");
        ConversationStore store = new ConversationStore(dataDir);
        List<Map<String, Object>> entries = store.loadMostRecentMessages();

        if (entries.isEmpty()) {
            log.info("No previous conversation to restore");
            return;
        }

        List<Msg> msgs = entries.stream()
                .map(e -> {
                    String role = (String) e.get("role");
                    String content = (String) e.get("content");
                    if (role == null || content == null) return null;
                    MsgRole msgRole = switch (role) {
                        case "user" -> MsgRole.USER;
                        case "assistant" -> MsgRole.ASSISTANT;
                        case "system" -> MsgRole.SYSTEM;
                        default -> null;
                    };
                    return msgRole != null ? Msg.of(msgRole, content) : null;
                })
                .filter(m -> m != null)
                .toList();

        if (msgs.size() > MAX_RESTORE_MESSAGES) {
            msgs = msgs.subList(msgs.size() - MAX_RESTORE_MESSAGES, msgs.size());
            log.info("Trimmed conversation history to last {} messages", MAX_RESTORE_MESSAGES);
        }

        if (!msgs.isEmpty()) {
            agent.injectMessages(msgs);
            log.info("Restored {} messages from previous conversation", msgs.size());
        }
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null && !val.isBlank() ? val : defaultValue;
    }

    public static void main(String[] args) {
        SpringApplication.run(KairoAssistantServer.class, args);
    }
}
