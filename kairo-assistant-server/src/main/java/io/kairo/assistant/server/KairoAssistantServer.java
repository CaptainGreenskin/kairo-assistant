package io.kairo.assistant.server;

import io.kairo.assistant.agent.AssistantAgentFactory;
import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class KairoAssistantServer {

    @Bean
    public AssistantSession assistantSession() {
        AssistantConfig config = AssistantConfig.builder()
                .modelProvider(env("KAIRO_PROVIDER", "anthropic"))
                .modelName(env("KAIRO_MODEL", "claude-sonnet-4-6"))
                .build();

        AssistantSession session = AssistantAgentFactory.create(config);
        session.start();
        return session;
    }

    @Bean
    public SessionManager sessionManager(AssistantSession session) {
        return new SessionManager(session);
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null && !val.isBlank() ? val : defaultValue;
    }

    public static void main(String[] args) {
        SpringApplication.run(KairoAssistantServer.class, args);
    }
}
