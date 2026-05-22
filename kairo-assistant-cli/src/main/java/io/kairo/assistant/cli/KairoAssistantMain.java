package io.kairo.assistant.cli;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.assistant.agent.AssistantAgentFactory;
import io.kairo.assistant.agent.AssistantConfig;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.SessionStore;
import io.kairo.assistant.tool.ToolCallLogger;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "kairo-assistant",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "Kairo Assistant — a personal AI assistant built on Kairo framework")
public class KairoAssistantMain implements Runnable {

    @Option(
            names = "--provider",
            description =
                    "Model provider (anthropic, openai, gemini, glm, minimax, deepseek, ...)",
            defaultValue = "anthropic")
    private String provider;

    @Option(names = "--model", description = "Model name", defaultValue = "claude-sonnet-4-6")
    private String model;

    @Option(names = "--api-key", description = "API key (or set env var)")
    private String apiKey;

    @Option(
            names = "--api-base-url",
            description = "API base URL (for OpenAI-compatible providers)")
    private String apiBaseUrl;

    @Option(names = "--data-dir", description = "Data directory for notes/reminders/sessions")
    private String dataDir;

    @Option(
            names = "--daemon",
            description = "Run in daemon mode (background cron + channel listeners)")
    private boolean daemon;

    @Option(
            names = {"--print", "-p", "--task"},
            description =
                    "One-shot prompt (non-interactive). Final response goes to stdout; tool"
                            + " calls are emitted as [tool: NAME] lines before the response.")
    private String printPrompt;

    @Option(
            names = "--session-id",
            description =
                    "Persist + resume conversation under <data-dir>/sessions/<id>.jsonl."
                            + " Combined with --print, enables scripted multi-turn flows.")
    private String sessionId;

    @Option(
            names = "--max-iterations",
            description = "Max ReAct loop iterations",
            defaultValue = "30")
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

        if (printPrompt != null) {
            runOneShot(session, printPrompt);
        } else if (daemon) {
            runDaemon(session);
        } else {
            new ReplSession(session).run();
        }
    }

    /**
     * One-shot mode: subscribe to the tool-call logger, send {@code prompt} to the agent, emit
     * {@code [tool: NAME]} for every tool the agent invokes, then print the final assistant text
     * on its own line. When {@code --session-id} is supplied, append the new turn back to the
     * session log so the next invocation can resume.
     *
     * <p>Output shape is stable for scripting / eval consumption:
     *
     * <pre>
     * [tool: search]
     * [tool: send_message]
     *
     * &lt;final assistant response text&gt;
     * </pre>
     *
     * <p>The blank line after the last tool marker separates the structured tool log from the
     * free-text response.
     */
    private void runOneShot(AssistantSession session, String prompt) {
        SessionStore store = sessionId != null ? sessionStoreFor(session) : null;
        try {
            installToolLogger(session);

            Msg userMsg = Msg.of(MsgRole.USER, prompt);
            List<Msg> toAppend = new ArrayList<>();
            toAppend.add(userMsg);

            // Replay note: --session-id today persists turns but the agent's per-call memory
            // bridge is what actually feeds prior context into the next call. Loading and
            // re-injecting full history here would double-count messages already in
            // MemoryStore. The on-disk JSONL exists for external inspection / eval re-scoring.
            var result = session.agent().call(userMsg).block();
            String responseText = result != null ? result.text() : "";

            System.out.println();
            System.out.println(responseText == null ? "" : responseText);

            if (store != null) {
                toAppend.add(Msg.of(MsgRole.ASSISTANT, responseText == null ? "" : responseText));
                store.append(sessionId, toAppend);
            }
        } finally {
            session.stop();
        }
    }

    private SessionStore sessionStoreFor(AssistantSession session) {
        String dir =
                session.config().dataDir() != null
                        ? session.config().dataDir()
                        : System.getProperty("user.home") + "/.kairo-assistant";
        return new SessionStore(Paths.get(dir));
    }

    /**
     * Subscribe to the {@link ToolCallLogger} (if present) and print one {@code [tool: NAME]}
     * line per tool invocation. The eval runner parses these to verify which tools the agent
     * called.
     */
    private void installToolLogger(AssistantSession session) {
        ToolExecutor te = session.toolExecutor();
        if (te instanceof ToolCallLogger logger) {
            logger.addListener(rec -> System.out.println("[tool: " + rec.toolName() + "]"));
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
