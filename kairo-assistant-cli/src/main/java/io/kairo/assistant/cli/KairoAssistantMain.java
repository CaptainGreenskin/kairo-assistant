package io.kairo.assistant.cli;

import io.kairo.acp.server.AcpStdioServer;
import io.kairo.acp.server.DefaultAcpAgent;
import io.kairo.acp.server.StreamingAcpBridge;
import io.kairo.api.acp.AcpCapabilities;
import io.kairo.api.acp.AcpImplementation;
import io.kairo.api.acp.AcpSessionUpdate;
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
            names = "--acp-server",
            description =
                    "Run as an Agent Client Protocol stdio server. Reads JSON-RPC 2.0 frames"
                            + " from stdin, writes responses + session/update notifications to"
                            + " stdout. Use this when an editor (Zed, OpenCode, ...) spawns"
                            + " kairo-assistant as a subprocess.")
    private boolean acpServer;

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

        if (acpServer) {
            runAcpServer(session);
        } else if (printPrompt != null) {
            runOneShot(session, printPrompt);
        } else if (daemon) {
            runDaemon(session);
        } else {
            new ReplSession(session).run();
        }
    }

    /**
     * Serve as an ACP stdio server. The editor spawns this process and drives it through
     * JSON-RPC; stdout becomes a protocol stream (logging must go to stderr).
     *
     * <p>Wires a {@link StreamingAcpBridge} that forwards live {@link ToolCallLogger}
     * events as ACP {@code tool_call_start} / {@code tool_call_complete} updates so the
     * editor's tool-call cards show what the assistant is doing in real time.
     */
    private void runAcpServer(AssistantSession session) {
        try {
            StreamingAcpBridge bridge = makeToolEventBridge(session);
            DefaultAcpAgent acpAgent =
                    new DefaultAcpAgent(
                            session.agent(),
                            new io.kairo.acp.server.AcpSessionManager(),
                            new AcpImplementation("kairo-assistant", "0.1.0"),
                            AcpCapabilities.textOnly(),
                            bridge);
            new AcpStdioServer(acpAgent).serve();
        } catch (Exception e) {
            System.err.println("ACP server failed: " + e.getMessage());
        } finally {
            session.stop();
        }
    }

    /**
     * Build a streaming bridge that forwards ToolCallLogger events to the editor as ACP tool-
     * call cards. Returns null when the session's tool executor isn't a ToolCallLogger; the ACP
     * server then falls back to single-chunk mode.
     */
    private StreamingAcpBridge makeToolEventBridge(AssistantSession session) {
        ToolExecutor te = session.toolExecutor();
        if (!(te instanceof ToolCallLogger logger)) return null;
        return (sid, sink) -> {
            java.util.function.Consumer<ToolCallLogger.ToolCallRecord> listener =
                    record -> {
                        String callId =
                                record.toolName() + "-" + record.timestamp().toEpochMilli();
                        sink.accept(
                                new AcpSessionUpdate.ToolCallStart(
                                        sid, callId, record.toolName(), java.util.Map.of()));
                        sink.accept(
                                new AcpSessionUpdate.ToolCallComplete(
                                        sid,
                                        callId,
                                        record.success(),
                                        record.success()
                                                ? "(" + record.durationMs() + " ms)"
                                                : record.error()));
                    };
            logger.addListener(listener);
            return () -> logger.removeListener(listener);
        };
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
        long t0 = System.nanoTime();
        boolean trace =
                "1".equals(System.getenv("KAIRO_PERF_TRACE"))
                        || "true".equalsIgnoreCase(System.getProperty("kairo.perf.trace"));
        if (trace) {
            System.err.printf(
                    "[perf] main entry  +%dms since JVM start%n",
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        }
        int exitCode = new CommandLine(new KairoAssistantMain()).execute(args);
        if (trace) {
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            System.err.printf("[perf] main exit  total since main=%dms%n", elapsed);
        }
        System.exit(exitCode);
    }
}
