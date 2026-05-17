package io.kairo.assistant.cli;

import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantAgentFactory;
import io.kairo.assistant.agent.AssistantSession;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "kairo-assistant",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "Kairo Assistant — a personal AI assistant built on Kairo framework")
public class KairoAssistantMain implements Runnable {

    @Option(names = "--provider", description = "Model provider (anthropic, openai, glm, minimax, deepseek)", defaultValue = "anthropic")
    private String provider;

    @Option(names = "--model", description = "Model name", defaultValue = "claude-sonnet-4-6")
    private String model;

    @Option(names = "--api-key", description = "API key (or set env var)")
    private String apiKey;

    @Option(names = "--api-base-url", description = "API base URL (for OpenAI-compatible providers)")
    private String apiBaseUrl;

    @Option(names = "--data-dir", description = "Data directory for notes/reminders")
    private String dataDir;

    @Option(names = "--daemon", description = "Run in daemon mode (background cron + channel listeners)")
    private boolean daemon;

    @Option(names = "--task", description = "One-shot task (non-interactive)")
    private String task;

    @Option(names = "--max-iterations", description = "Max ReAct loop iterations", defaultValue = "30")
    private int maxIterations;

    @Option(names = "--timeout", description = "Timeout in seconds", defaultValue = "600")
    private int timeoutSeconds;

    @Override
    public void run() {
        AssistantConfig.Builder configBuilder =
                AssistantConfig.builder()
                        .modelProvider(provider)
                        .modelName(model)
                        .maxIterations(maxIterations)
                        .timeout(java.time.Duration.ofSeconds(timeoutSeconds));

        if (apiKey != null) {
            configBuilder.apiKey(apiKey);
        }
        if (apiBaseUrl != null) {
            configBuilder.apiBaseUrl(apiBaseUrl);
        }
        if (dataDir != null) {
            configBuilder.dataDir(dataDir);
        }

        AssistantConfig config = configBuilder.build();
        AssistantSession session = AssistantAgentFactory.create(config);
        session.start();

        Runtime.getRuntime().addShutdownHook(new Thread(session::stop));

        if (task != null) {
            runOneShot(session, task);
        } else if (daemon) {
            runDaemon(session);
        } else {
            new ReplSession(session).run();
        }
    }

    private void runOneShot(AssistantSession session, String taskText) {
        try {
            var msg = io.kairo.api.message.Msg.of(io.kairo.api.message.MsgRole.USER, taskText);
            var result = session.agent().call(msg).block();
            if (result != null) {
                System.out.println(result.text());
            }
        } finally {
            session.stop();
        }
    }

    private void runDaemon(AssistantSession session) {
        System.out.println("Kairo Assistant daemon started. Press Ctrl+C to stop.");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            session.stop();
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new KairoAssistantMain()).execute(args);
        System.exit(exitCode);
    }
}
