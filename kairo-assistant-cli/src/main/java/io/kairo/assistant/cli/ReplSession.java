package io.kairo.assistant.cli;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.ConversationStore;
import io.kairo.core.agent.DefaultReActAgent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class ReplSession {

    private static final Logger log = LoggerFactory.getLogger(ReplSession.class);

    private final AssistantSession session;
    private final ConversationStore conversationStore;
    private final List<Map<String, String>> history = new ArrayList<>();
    private final AtomicBoolean verbose = new AtomicBoolean(false);
    private final AtomicBoolean renderMarkdown = new AtomicBoolean(true);
    private final AtomicBoolean notifyOnComplete = new AtomicBoolean(false);
    private int totalInputChars = 0;
    private int totalOutputChars = 0;
    private int exchangeCount = 0;
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private boolean multiLineMode = false;
    private final StringBuilder multiLineBuffer = new StringBuilder();
    private final Map<String, String> snippets = new LinkedHashMap<>();
    private volatile java.util.Timer watchTimer = null;
    private String activeProfile = "default";
    private final List<Long> responseTimes = new ArrayList<>();
    private boolean timerEnabled = false;
    private final List<Map<String, String>> bookmarks = new ArrayList<>();
    private final java.time.Instant sessionStartTime = java.time.Instant.now();
    private final Map<String, String> templates = new LinkedHashMap<>();
    private final Map<String, java.util.Timer> scheduledTasks = new ConcurrentHashMap<>();
    private int scheduleCounter = 0;
    private final Map<String, List<String>> macros = new LinkedHashMap<>();
    private List<String> recordingMacro = null;
    private String recordingMacroName = null;
    private String outputFormat = "text";
    private String theme = "default";
    private final List<String> pinnedMessages = new ArrayList<>();
    private final List<String> recentCommands = new ArrayList<>();
    private ToolCategory focusCategory = null;
    private final AtomicReference<Thread> activeAgentThread = new AtomicReference<>();
    private Terminal terminal;

    public ReplSession(AssistantSession session) {
        this.session = session;
        this.conversationStore = new ConversationStore(
                Path.of(session.config().dataDir(), "conversations"));
        this.conversationStore.startSession();
        loadAliases();
        loadSnippets();
    }

    public void run() {
        try {
            terminal = TerminalBuilder.builder().system(true).build();

            Path historyFile = Path.of(session.config().dataDir(), ".repl_history");
            Completer slashCompleter = new StringsCompleter(
                    "/help", "/status", "/tools", "/skills", "/config", "/model",
                    "/history", "/clear", "/verbose", "/permissions", "/channels",
                    "/plugins", "/plugin", "/version", "/interrupt", "/sessions", "/resume",
                    "/search", "/export", "/delete", "/render", "/system",
                    "/retry", "/cost", "/notify", "/alias", "/multiline",
                    "/context", "/undo", "/run", "/snippet", "/watch",
                    "/file", "/pipe", "/profile", "/env", "/timer",
                    "/bookmark", "/template", "/stats", "/diff",
                    "/schedule", "/chain", "/macro", "/format",
                    "/theme", "/log", "/feedback", "/pin", "/playground",
                    "/translate", "/summarize", "/whoami", "/doctor",
                    "/compact", "/recent", "/focus", "/agenda",
                    "/benchmark", "/top", "/replay", "/usage", "/quit", "/exit");
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(slashCompleter)
                    .variable(LineReader.HISTORY_FILE, historyFile)
                    .build();

            wireStreaming();
            wireInterruptHandler();
            printBanner();

            while (true) {
                String line;
                try {
                    line = reader.readLine("assistant> ").trim();
                } catch (UserInterruptException e) {
                    break;
                } catch (EndOfFileException e) {
                    break;
                }

                if (line.isEmpty()) continue;

                line = expandAlias(line);

                if (multiLineMode && !line.equals("///")) {
                    multiLineBuffer.append(line).append("\n");
                    continue;
                }
                if (multiLineMode && line.equals("///")) {
                    line = multiLineBuffer.toString().trim();
                    multiLineBuffer.setLength(0);
                    multiLineMode = false;
                    if (line.isEmpty()) continue;
                }

                recentCommands.add(line);
                if (recentCommands.size() > 50) recentCommands.remove(0);

                if (line.startsWith("/")) {
                    if (handleSlashCommand(line, reader)) continue;
                }

                executeWithInterrupt(line, reader);
                terminal.writer().flush();
            }

            terminal.writer().println("Goodbye!");
            terminal.writer().flush();
        } catch (Exception e) {
            System.err.println("Failed to initialize terminal: " + e.getMessage());
        }
    }

    private void executeWithInterrupt(String line, LineReader reader) {
        Msg input = Msg.of(MsgRole.USER, line);
        long startMs = System.currentTimeMillis();
        CompletableFuture<Msg> future = session.agent().call(input).toFuture();
        activeAgentThread.set(Thread.currentThread());

        try {
            while (!future.isDone()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    session.agent().interrupt();
                    terminal.writer().println("\n[Interrupted]");
                    terminal.writer().flush();
                    break;
                }
            }

            if (future.isDone() && !future.isCompletedExceptionally()) {
                Msg response = future.get();
                if (response != null) {
                    long elapsed = System.currentTimeMillis() - startMs;
                    responseTimes.add(elapsed);
                    terminal.writer().println();
                    if (timerEnabled) {
                        terminal.writer().printf("[%dms]%n", elapsed);
                    }
                    history.add(Map.of("role", "user", "content", line));
                    history.add(Map.of("role", "assistant", "content", response.text()));
                    conversationStore.appendMessage("user", line);
                    conversationStore.appendMessage("assistant", response.text());
                    totalInputChars += line.length();
                    totalOutputChars += response.text().length();
                    exchangeCount++;
                    if (notifyOnComplete.get()) {
                        terminal.writer().print("\007");
                        terminal.writer().flush();
                    }
                }
            }
        } catch (ExecutionException e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if (msg != null && msg.contains("interrupt")) {
                terminal.writer().println("[Interrupted]");
            } else {
                terminal.writer().println("Error: " + msg);
            }
        } catch (Exception e) {
            terminal.writer().println("Error: " + e.getMessage());
        } finally {
            activeAgentThread.set(null);
        }
    }

    private void wireInterruptHandler() {
        try {
            Signal.handle(new Signal("INT"), sig -> {
                Thread t = activeAgentThread.get();
                if (t != null) {
                    t.interrupt();
                }
            });
        } catch (IllegalArgumentException ignored) {
            // Signal handling not supported on this platform
        }
    }

    private void wireStreaming() {
        if (session.agent() instanceof DefaultReActAgent agent) {
            Consumer<String> deltaConsumer = delta -> {
                if (terminal != null) {
                    terminal.writer().print(delta);
                    terminal.writer().flush();
                }
            };
            agent.setTextDeltaConsumer(deltaConsumer);
        }
    }

    private boolean handleSlashCommand(String line, LineReader reader) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        if (recordingMacro != null && !cmd.equals("/macro")) {
            recordingMacro.add(line);
            terminal.writer().println("[macro] Recorded: " + line);
            terminal.writer().flush();
        }

        switch (cmd) {
            case "/quit", "/exit" -> {
                terminal.writer().println("Goodbye!");
                terminal.writer().flush();
                System.exit(0);
            }
            case "/help" -> printHelp();
            case "/status" -> printStatus();
            case "/tools" -> printTools(arg);
            case "/skills" -> printSkills();
            case "/config" -> printConfig();
            case "/model" -> printModel();
            case "/history" -> printHistory(arg);
            case "/clear" -> clearHistory();
            case "/verbose" -> toggleVerbose();
            case "/permissions" -> printPermissions();
            case "/channels" -> printChannels();
            case "/plugins" -> printPlugins();
            case "/plugin" -> handlePluginCommand(arg);
            case "/version" -> printVersion();
            case "/interrupt" -> doInterrupt();
            case "/sessions" -> printSessions();
            case "/resume" -> resumeSession(arg);
            case "/search" -> searchConversations(arg);
            case "/export" -> exportSession(arg);
            case "/delete" -> deleteSession(arg);
            case "/render" -> toggleRender();
            case "/system" -> handleSystemPrompt(arg);
            case "/retry" -> retryLast(reader);
            case "/cost" -> printCost();
            case "/notify" -> toggleNotify();
            case "/alias" -> handleAlias(arg);
            case "/multiline" -> toggleMultiLine();
            case "/context" -> handleContext(arg);
            case "/undo" -> undoLastExchange();
            case "/run" -> runToolDirect(arg);
            case "/snippet" -> handleSnippet(arg);
            case "/watch" -> handleWatch(arg);
            case "/file" -> handleFile(arg);
            case "/pipe" -> handlePipe(arg);
            case "/profile" -> handleProfile(arg);
            case "/env" -> printEnv();
            case "/timer" -> handleTimer(arg);
            case "/bookmark" -> handleBookmark(arg);
            case "/template" -> handleTemplate(arg, reader);
            case "/stats" -> printStats();
            case "/diff" -> diffSessions(arg);
            case "/schedule" -> handleSchedule(arg);
            case "/chain" -> handleChain(arg);
            case "/macro" -> handleMacro(arg, reader);
            case "/format" -> handleFormat(arg);
            case "/theme" -> handleTheme(arg);
            case "/log" -> handleLog(arg);
            case "/feedback" -> handleFeedback(arg, reader);
            case "/pin" -> handlePin(arg);
            case "/playground" -> handlePlayground(arg, reader);
            case "/translate" -> handleTranslate(arg, reader);
            case "/summarize" -> handleSummarize(reader);
            case "/whoami" -> handleWhoami();
            case "/doctor" -> handleDoctor();
            case "/compact" -> handleCompact(reader);
            case "/recent" -> handleRecent(arg, reader);
            case "/focus" -> handleFocus(arg);
            case "/agenda" -> handleAgenda();
            case "/benchmark" -> handleBenchmark();
            case "/top" -> handleTop();
            case "/replay" -> handleReplay(arg);
            case "/usage" -> handleUsage();
            default -> {
                terminal.writer().println("Unknown command: " + cmd + ". Type /help for available commands.");
                terminal.writer().flush();
            }
        }
        return true;
    }

    private void printStatus() {
        var w = terminal.writer();
        w.println();
        w.println("=== Kairo Assistant Status ===");
        w.printf("  Provider:    %s%n", session.config().modelProvider());
        w.printf("  Model:       %s%n", session.config().modelName());
        w.printf("  Tools:       %d registered%n", session.toolRegistry().getAll().size());
        w.printf("  Skills:      %d registered%n", session.skillRegistry().list().size());
        w.printf("  Plugins:     %d loaded%n",
                session.pluginManager() != null ? session.pluginManager().plugins().size() : 0);
        w.printf("  History:     %d exchanges%n", history.size() / 2);
        w.printf("  Verbose:     %s%n", verbose.get());
        w.println();
        w.flush();
    }

    private void printTools(String filter) {
        var w = terminal.writer();
        List<ToolDefinition> tools = session.toolRegistry().getAll();
        List<ToolDefinition> filtered = tools.stream()
                .filter(t -> focusCategory == null || t.category() == focusCategory)
                .filter(t -> filter.isEmpty()
                        || t.name().contains(filter)
                        || t.category().name().toLowerCase().contains(filter.toLowerCase()))
                .toList();
        w.println();
        if (focusCategory != null) {
            w.printf("=== Tools (%d/%d) [focus: %s] ===%n",
                    filtered.size(), tools.size(), focusCategory.name().toLowerCase());
        } else {
            w.printf("=== Tools (%d) ===%n", filtered.size());
        }
        for (ToolDefinition tool : filtered) {
            w.printf("  %-20s [%s] %s%n",
                    tool.name(),
                    tool.category().name().toLowerCase(),
                    tool.description().length() > 60
                            ? tool.description().substring(0, 57) + "..."
                            : tool.description());
        }
        w.println();
        w.flush();
    }

    private void printSkills() {
        var w = terminal.writer();
        List<SkillDefinition> skills = session.skillRegistry().list();
        w.println();
        w.printf("=== Skills (%d) ===%n", skills.size());
        for (SkillDefinition skill : skills) {
            w.printf("  /%-18s %s%n", skill.name(), skill.description());
        }
        w.println();
        w.flush();
    }

    private void printConfig() {
        var w = terminal.writer();
        var c = session.config();
        w.println();
        w.println("=== Configuration ===");
        w.printf("  Provider:       %s%n", c.modelProvider());
        w.printf("  Model:          %s%n", c.modelName());
        w.printf("  Max Iterations: %d%n", c.maxIterations());
        w.printf("  Timeout:        %s%n", c.timeout());
        w.printf("  Token Budget:   %d%n", c.tokenBudget());
        w.printf("  Data Dir:       %s%n", c.dataDir());
        w.println();
        w.flush();
    }

    private void printModel() {
        var w = terminal.writer();
        w.println();
        w.printf("  Current model: %s (%s)%n", session.config().modelName(), session.config().modelProvider());
        w.println();
        w.flush();
    }

    private void printHistory(String arg) {
        var w = terminal.writer();
        int count = 10;
        if (!arg.isEmpty()) {
            try { count = Integer.parseInt(arg); } catch (NumberFormatException ignored) {}
        }

        w.println();
        w.println("=== Recent History ===");
        int start = Math.max(0, history.size() - count * 2);
        for (int i = start; i < history.size(); i++) {
            var entry = history.get(i);
            String role = entry.get("role");
            String content = entry.get("content");
            String prefix = "user".equals(role) ? "  You: " : "  AI:  ";
            String display = content.length() > 100 ? content.substring(0, 97) + "..." : content;
            w.println(prefix + display);
        }
        if (history.isEmpty()) {
            w.println("  (no history yet)");
        }
        w.println();
        w.flush();
    }

    private void clearHistory() {
        history.clear();
        terminal.writer().println("History cleared.");
        terminal.writer().flush();
    }

    private void toggleVerbose() {
        boolean newVal = !verbose.get();
        verbose.set(newVal);
        terminal.writer().println("Verbose mode: " + (newVal ? "ON" : "OFF"));
        terminal.writer().flush();
    }

    private void printPermissions() {
        var w = terminal.writer();
        w.println();
        w.println("=== Permissions ===");
        w.println("  Mode: DEFAULT");
        w.println("  SYSTEM_CHANGE tools require approval");
        w.println("  WRITE tools auto-approved");
        w.println("  READ_ONLY tools auto-approved");
        w.println();
        w.flush();
    }

    private void printChannels() {
        var w = terminal.writer();
        w.println();
        w.println("=== Channels ===");
        w.println("  (channel listing requires runtime channel registry)");
        w.println();
        w.flush();
    }

    private void printPlugins() {
        var w = terminal.writer();
        w.println();
        if (session.pluginManager() == null) {
            w.println("  Plugin system not initialized");
        } else {
            var installations = session.pluginManager().list();
            w.printf("=== Plugins (%d) ===%n", installations.size());
            for (var inst : installations) {
                String enabledMark = inst.enabled() ? "*" : " ";
                w.printf(
                        "  [%s] %-25s %s  (%s, %s)%n",
                        enabledMark,
                        inst.metadata().name(),
                        inst.metadata().version(),
                        inst.source().type(),
                        inst.scope());
            }
            if (installations.isEmpty()) {
                w.println("  (no plugins installed — try /plugin install <source>)");
            }
        }
        w.println();
        w.flush();
    }

    private void handlePluginCommand(String arg) {
        var w = terminal.writer();
        if (session.pluginManager() == null) {
            w.println("  Plugin system not initialized");
            w.flush();
            return;
        }
        if (arg == null || arg.isBlank()) {
            printPluginUsage();
            return;
        }
        String[] parts = arg.trim().split("\\s+", 2);
        String sub = parts[0];
        String rest = parts.length > 1 ? parts[1] : "";
        switch (sub) {
            case "list" -> printPlugins();
            case "install" -> pluginInstall(rest);
            case "enable" -> pluginEnable(rest);
            case "disable" -> pluginDisable(rest);
            case "uninstall", "remove" -> pluginUninstall(rest);
            case "update" -> pluginUpdate(rest);
            default -> {
                w.println("  Unknown subcommand: " + sub);
                printPluginUsage();
            }
        }
    }

    private void printPluginUsage() {
        var w = terminal.writer();
        w.println();
        w.println("Plugin commands:");
        w.println("  /plugin list                       List installed plugins");
        w.println("  /plugin install <source>           Install (path / github:owner/repo / git+url / npm:pkg@ver)");
        w.println("  /plugin enable <id-prefix>         Enable a plugin");
        w.println("  /plugin disable <id-prefix>        Disable a plugin (keeps install)");
        w.println("  /plugin uninstall <id-prefix>      Uninstall a plugin");
        w.println("  /plugin update <id-prefix>         Re-load manifest from disk");
        w.println();
        w.flush();
    }

    private void pluginInstall(String spec) {
        var w = terminal.writer();
        if (spec == null || spec.isBlank()) {
            w.println("  Usage: /plugin install <source>");
            w.flush();
            return;
        }
        io.kairo.api.plugin.PluginSource source;
        try {
            source = parsePluginSourceSpec(spec);
        } catch (IllegalArgumentException e) {
            w.println("  " + e.getMessage());
            w.flush();
            return;
        }
        try {
            var inst = session.pluginManager()
                    .install(source, io.kairo.api.plugin.PluginScope.PROJECT)
                    .block(java.time.Duration.ofMinutes(2));
            w.printf(
                    "  Installed '%s' v%s (id=%s, source=%s)%n",
                    inst.metadata().name(), inst.metadata().version(), inst.id(), inst.source().type());
        } catch (Exception e) {
            w.println("  Install failed: " + rootCauseMessage(e));
        }
        w.flush();
    }

    private void pluginEnable(String idPrefix) {
        applyToResolvedPlugin(idPrefix, "enable", id -> session.pluginManager().enable(id).block(java.time.Duration.ofMinutes(2)));
    }

    private void pluginDisable(String idPrefix) {
        applyToResolvedPlugin(idPrefix, "disable", id -> session.pluginManager().disable(id).block(java.time.Duration.ofSeconds(30)));
    }

    private void pluginUninstall(String idPrefix) {
        applyToResolvedPlugin(idPrefix, "uninstall", id -> session.pluginManager().uninstall(id).block(java.time.Duration.ofSeconds(30)));
    }

    private void pluginUpdate(String idPrefix) {
        applyToResolvedPlugin(idPrefix, "update", id -> session.pluginManager().update(id).block(java.time.Duration.ofMinutes(2)));
    }

    private void applyToResolvedPlugin(String idPrefix, String label, java.util.function.Consumer<String> action) {
        var w = terminal.writer();
        if (idPrefix == null || idPrefix.isBlank()) {
            w.println("  Usage: /plugin " + label + " <id-prefix>");
            w.flush();
            return;
        }
        String id = resolvePluginId(idPrefix);
        if (id == null) {
            w.println("  No plugin matches: " + idPrefix);
            w.flush();
            return;
        }
        try {
            action.accept(id);
            w.printf("  %s: %s%n", label.substring(0, 1).toUpperCase() + label.substring(1) + "d", id);
        } catch (Exception e) {
            w.println("  " + label + " failed: " + rootCauseMessage(e));
        }
        w.flush();
    }

    /** Resolves a user-supplied prefix to a unique plugin id, or null if 0/multiple match. */
    private String resolvePluginId(String prefix) {
        var matches = session.pluginManager().list().stream()
                .filter(p -> p.id().contains(prefix) || p.metadata().name().equals(prefix))
                .toList();
        if (matches.size() == 1) return matches.get(0).id();
        return null;
    }

    /**
     * Parses CLI shorthand into a PluginSource:
     * <ul>
     *   <li>{@code github:owner/repo} or {@code github:owner/repo@ref}
     *   <li>{@code npm:pkg@version} or {@code npm:@scope/pkg@version}
     *   <li>{@code git+<url>} or {@code git+<url>@ref}
     *   <li>{@code git-subdir+<url>@<ref>:<subdir>}
     *   <li>anything else → LocalPath (resolved against cwd if relative)
     * </ul>
     */
    static io.kairo.api.plugin.PluginSource parsePluginSourceSpec(String spec) {
        if (spec.startsWith("github:")) {
            String body = spec.substring("github:".length());
            int at = body.lastIndexOf('@');
            String repo = at < 0 ? body : body.substring(0, at);
            String ref = at < 0 ? null : body.substring(at + 1);
            return new io.kairo.api.plugin.PluginSource.GitHub(repo, ref, null);
        }
        if (spec.startsWith("npm:")) {
            String body = spec.substring("npm:".length());
            int at = body.lastIndexOf('@');
            // For scoped packages '@scope/pkg@version', the LAST @ is the version separator only
            // when it's not the leading @ of the scope.
            if (at <= 0 || (at == 0 && body.startsWith("@"))) {
                throw new IllegalArgumentException("npm spec must include @version: npm:<pkg>@<version>");
            }
            String pkg = body.substring(0, at);
            String version = body.substring(at + 1);
            return new io.kairo.api.plugin.PluginSource.Npm(pkg, version, null);
        }
        if (spec.startsWith("git-subdir+")) {
            String body = spec.substring("git-subdir+".length());
            // Find the LAST ':' whose next char is NOT '/' — distinguishes the subdir
            // separator from the '://' inside an https/git URL.
            int colon = -1;
            for (int i = body.length() - 1; i >= 0; i--) {
                if (body.charAt(i) == ':'
                        && (i + 1 >= body.length() || body.charAt(i + 1) != '/')) {
                    colon = i;
                    break;
                }
            }
            if (colon < 0) {
                throw new IllegalArgumentException("git-subdir spec needs ':<subdir>' suffix");
            }
            String urlAndRef = body.substring(0, colon);
            String subdir = body.substring(colon + 1);
            int at = urlAndRef.lastIndexOf('@');
            String url = at < 0 ? urlAndRef : urlAndRef.substring(0, at);
            String ref = at < 0 ? null : urlAndRef.substring(at + 1);
            return new io.kairo.api.plugin.PluginSource.GitSubdir(url, ref, subdir);
        }
        if (spec.startsWith("git+")) {
            String body = spec.substring("git+".length());
            int at = body.lastIndexOf('@');
            // For URLs like https://x.com/repo.git the @ rarely appears; if it does after the
            // schema separator, treat as ref.
            String url;
            String ref;
            if (at > 5 /* skip 'https' length */) {
                url = body.substring(0, at);
                ref = body.substring(at + 1);
            } else {
                url = body;
                ref = null;
            }
            return new io.kairo.api.plugin.PluginSource.GitUrl(url, ref);
        }
        // Fallback: local path.
        Path p = Path.of(spec);
        if (!p.isAbsolute()) p = Path.of("").toAbsolutePath().resolve(spec);
        return new io.kairo.api.plugin.PluginSource.LocalPath(p);
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private void printVersion() {
        terminal.writer().println("  Kairo Assistant v0.1.0 (built on Kairo Framework)");
        terminal.writer().flush();
    }

    private void doInterrupt() {
        session.agent().interrupt();
        terminal.writer().println("[Agent interrupted]");
        terminal.writer().flush();
    }

    private void printSessions() {
        var w = terminal.writer();
        var sessions = conversationStore.listSessions();
        w.println();
        w.printf("=== Sessions (%d) ===%n", sessions.size());
        String currentId = conversationStore.currentSessionId();
        for (var s : sessions) {
            String marker = s.get("id").equals(currentId) ? " *" : "  ";
            w.printf("%s%-10s %s%n", marker, s.get("id"), s.get("preview"));
        }
        if (sessions.isEmpty()) {
            w.println("  (no saved sessions)");
        }
        w.println();
        w.flush();
    }

    private void resumeSession(String sessionId) {
        var w = terminal.writer();
        if (sessionId.isEmpty()) {
            w.println("Usage: /resume <session-id>");
            w.println("Use /sessions to list available sessions.");
            w.flush();
            return;
        }

        var entries = conversationStore.loadSession(sessionId);
        if (entries.isEmpty()) {
            w.println("Session not found: " + sessionId);
            w.flush();
            return;
        }

        history.clear();
        w.println();
        w.println("=== Resumed session " + sessionId + " ===");
        for (var entry : entries) {
            String type = (String) entry.get("type");
            if (!"message".equals(type)) continue;

            String role = (String) entry.get("role");
            String content = (String) entry.get("content");
            history.add(Map.of("role", role, "content", content));

            String prefix = "user".equals(role) ? "  You: " : "  AI:  ";
            String display = content.length() > 100 ? content.substring(0, 97) + "..." : content;
            w.println(prefix + display);
        }
        w.println("=== End of history ===");
        w.println();
        w.flush();
    }

    private void exportSession(String arg) {
        var w = terminal.writer();
        String[] parts = arg.split("\\s+", 2);
        String sessionId = parts[0];
        String format = parts.length > 1 ? parts[1].trim() : "markdown";

        if (sessionId.isEmpty()) {
            sessionId = conversationStore.currentSessionId();
            if (sessionId == null) {
                w.println("No active session. Usage: /export [session-id] [markdown|json]");
                w.flush();
                return;
            }
        }

        String content = conversationStore.exportSession(sessionId, format);
        if (content == null) {
            w.println("Session not found or export failed: " + sessionId);
            w.flush();
            return;
        }

        String ext = "json".equalsIgnoreCase(format) ? ".json" : ".md";
        Path exportFile = Path.of(session.config().dataDir(), "exports", sessionId + ext);
        try {
            Files.createDirectories(exportFile.getParent());
            Files.writeString(exportFile, content);
            w.println("Exported to: " + exportFile);
        } catch (IOException e) {
            w.println("Failed to write export file: " + e.getMessage());
        }
        w.flush();
    }

    private void deleteSession(String sessionId) {
        var w = terminal.writer();
        if (sessionId.isEmpty()) {
            w.println("Usage: /delete <session-id>");
            w.flush();
            return;
        }
        if (sessionId.equals(conversationStore.currentSessionId())) {
            w.println("Cannot delete the current active session.");
            w.flush();
            return;
        }
        if (conversationStore.deleteSession(sessionId)) {
            w.println("Session deleted: " + sessionId);
        } else {
            w.println("Session not found: " + sessionId);
        }
        w.flush();
    }

    private void toggleRender() {
        boolean newVal = !renderMarkdown.get();
        renderMarkdown.set(newVal);
        terminal.writer().println("Markdown rendering: " + (newVal ? "ON" : "OFF"));
        terminal.writer().flush();
    }

    private void retryLast(LineReader reader) {
        var w = terminal.writer();
        String lastUserMsg = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).get("role"))) {
                lastUserMsg = history.get(i).get("content");
                break;
            }
        }
        if (lastUserMsg == null) {
            w.println("No previous message to retry.");
            w.flush();
            return;
        }
        w.println("Retrying: " + (lastUserMsg.length() > 60 ? lastUserMsg.substring(0, 57) + "..." : lastUserMsg));
        w.flush();
        executeWithInterrupt(lastUserMsg, reader);
    }

    private void printCost() {
        var w = terminal.writer();
        w.println();
        w.println("=== Usage Estimate ===");
        int estInputTokens = totalInputChars / 4;
        int estOutputTokens = totalOutputChars / 4;
        w.printf("  Exchanges:       %d%n", exchangeCount);
        w.printf("  Input chars:     %,d (~%,d tokens)%n", totalInputChars, estInputTokens);
        w.printf("  Output chars:    %,d (~%,d tokens)%n", totalOutputChars, estOutputTokens);

        double inputCostPer1M = 3.0;
        double outputCostPer1M = 15.0;
        double estCost = (estInputTokens * inputCostPer1M + estOutputTokens * outputCostPer1M) / 1_000_000;
        w.printf("  Est. cost:       $%.4f (Sonnet pricing)%n", estCost);
        w.println();
        w.flush();
    }

    private void toggleNotify() {
        boolean newVal = !notifyOnComplete.get();
        notifyOnComplete.set(newVal);
        terminal.writer().println("Notification on completion: " + (newVal ? "ON" : "OFF"));
        terminal.writer().flush();
    }

    private void handleAlias(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty()) {
            w.println();
            w.println("=== Aliases ===");
            if (aliases.isEmpty()) {
                w.println("  (none defined)");
            } else {
                for (var entry : aliases.entrySet()) {
                    w.printf("  %-15s → %s%n", entry.getKey(), entry.getValue());
                }
            }
            w.println();
            w.println("Usage: /alias <name> <expansion>");
            w.println("       /alias remove <name>");
            w.flush();
            return;
        }

        String[] parts = arg.split("\\s+", 2);
        if ("remove".equals(parts[0]) && parts.length > 1) {
            String name = parts[1].trim();
            if (aliases.remove(name) != null) {
                w.println("Alias removed: " + name);
                saveAliases();
            } else {
                w.println("Alias not found: " + name);
            }
            w.flush();
            return;
        }

        if (parts.length < 2) {
            w.println("Usage: /alias <name> <expansion>");
            w.flush();
            return;
        }

        aliases.put(parts[0], parts[1]);
        saveAliases();
        w.println("Alias set: " + parts[0] + " → " + parts[1]);
        w.flush();
    }

    private String expandAlias(String line) {
        if (aliases.isEmpty()) return line;
        String[] parts = line.split("\\s+", 2);
        String expanded = aliases.get(parts[0]);
        if (expanded != null) {
            return parts.length > 1 ? expanded + " " + parts[1] : expanded;
        }
        return line;
    }

    private void loadAliases() {
        Path file = Path.of(session.config().dataDir(), "aliases.properties");
        if (Files.exists(file)) {
            try {
                Properties props = new Properties();
                props.load(Files.newBufferedReader(file));
                props.forEach((k, v) -> aliases.put(k.toString(), v.toString()));
            } catch (IOException e) {
                log.debug("Failed to load aliases: {}", e.getMessage());
            }
        }
    }

    private void saveAliases() {
        Path file = Path.of(session.config().dataDir(), "aliases.properties");
        try {
            Properties props = new Properties();
            aliases.forEach(props::setProperty);
            Files.createDirectories(file.getParent());
            props.store(Files.newBufferedWriter(file), "Kairo Assistant aliases");
        } catch (IOException e) {
            log.warn("Failed to save aliases: {}", e.getMessage());
        }
    }

    private void runToolDirect(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty()) {
            w.println("Usage: /run <tool_name> [arg1=val1] [arg2=val2] ...");
            w.println("Example: /run shell command=ls -la");
            w.println("         /run read_file path=/etc/hostname");
            w.flush();
            return;
        }

        String[] parts = arg.split("\\s+", 2);
        String toolName = parts[0];

        var toolDef = session.toolRegistry().get(toolName);
        if (toolDef.isEmpty()) {
            w.println("Tool not found: " + toolName);
            w.println("Use /tools to see available tools.");
            w.flush();
            return;
        }

        Map<String, Object> input = new LinkedHashMap<>();
        if (parts.length > 1) {
            String argsStr = parts[1];
            for (String kv : argsStr.split("\\s+(?=\\w+=)")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    input.put(kv.substring(0, eq), kv.substring(eq + 1));
                } else {
                    input.put("input", argsStr);
                    break;
                }
            }
        }

        w.printf("Running %s with %s...%n", toolName, input);
        w.flush();

        try {
            var result = session.toolExecutor().execute(toolName, input).block();
            if (result != null) {
                w.println();
                w.println(result.content());
                if (result.metadata() != null && !result.metadata().isEmpty()) {
                    w.println("[metadata: " + result.metadata() + "]");
                }
            }
        } catch (Exception e) {
            w.println("Error: " + e.getMessage());
        }
        w.println();
        w.flush();
    }

    private void handleContext(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty() || "show".equals(arg)) {
            w.println();
            w.println("=== Context Window ===");
            if (session.agent() instanceof DefaultReActAgent agent) {
                var msgs = agent.conversationHistory();
                int totalChars = 0;
                int userMsgs = 0;
                int assistantMsgs = 0;
                for (var msg : msgs) {
                    totalChars += msg.text() != null ? msg.text().length() : 0;
                    if (msg.role() == MsgRole.USER) userMsgs++;
                    else if (msg.role() == MsgRole.ASSISTANT) assistantMsgs++;
                }
                int estTokens = totalChars / 4;
                w.printf("  Messages:      %d total (%d user, %d assistant)%n", msgs.size(), userMsgs, assistantMsgs);
                w.printf("  Characters:    %,d%n", totalChars);
                w.printf("  Est. tokens:   ~%,d%n", estTokens);
                w.printf("  Budget:        %,d tokens%n", session.config().tokenBudget());
                if (session.config().tokenBudget() > 0) {
                    double pct = (double) estTokens / session.config().tokenBudget() * 100;
                    w.printf("  Usage:         %.1f%%%n", pct);
                }
            } else {
                w.println("  (context info not available for this agent type)");
            }
            w.println();
            w.println("Subcommands: /context [show|clear|inject <text>|last <n>]");
        } else if ("clear".equals(arg)) {
            if (session.agent() instanceof DefaultReActAgent agent) {
                agent.conversationHistory().clear();
                history.clear();
                exchangeCount = 0;
                w.println("Context cleared. Agent memory reset.");
            } else {
                w.println("Cannot clear context for this agent type.");
            }
        } else if (arg.startsWith("inject ")) {
            String text = arg.substring(7).trim();
            if (text.isEmpty()) {
                w.println("Usage: /context inject <system text>");
            } else if (session.agent() instanceof DefaultReActAgent agent) {
                agent.injectMessages(List.of(Msg.of(MsgRole.SYSTEM, text)));
                w.println("Injected system message (" + text.length() + " chars).");
            } else {
                w.println("Cannot inject for this agent type.");
            }
        } else if (arg.startsWith("last")) {
            String countStr = arg.replace("last", "").trim();
            int count = 5;
            try { if (!countStr.isEmpty()) count = Integer.parseInt(countStr); } catch (NumberFormatException ignored) {}
            if (session.agent() instanceof DefaultReActAgent agent) {
                var msgs = agent.conversationHistory();
                int start = Math.max(0, msgs.size() - count);
                w.println();
                w.printf("=== Last %d messages ===%n", Math.min(count, msgs.size()));
                for (int i = start; i < msgs.size(); i++) {
                    var msg = msgs.get(i);
                    String preview = msg.text() != null
                            ? (msg.text().length() > 80 ? msg.text().substring(0, 77) + "..." : msg.text())
                            : "(no text)";
                    w.printf("  [%s] %s%n", msg.role(), preview);
                }
            } else {
                w.println("Cannot show context for this agent type.");
            }
        } else {
            w.println("Usage: /context [show|clear|inject <text>|last <n>]");
        }
        w.flush();
    }

    private void undoLastExchange() {
        var w = terminal.writer();
        if (history.size() < 2) {
            w.println("No exchange to undo.");
            w.flush();
            return;
        }
        history.remove(history.size() - 1);
        history.remove(history.size() - 1);
        w.println("Last exchange removed from local history.");
        w.println("Note: Agent's internal context is not modified. Use /clear to reset fully.");
        w.flush();
    }

    private void handleSnippet(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty() || "list".equals(arg)) {
            w.println();
            w.println("=== Snippets ===");
            if (snippets.isEmpty()) {
                w.println("  (none saved)");
            } else {
                for (var entry : snippets.entrySet()) {
                    String preview = entry.getValue().length() > 60
                            ? entry.getValue().substring(0, 57) + "..." : entry.getValue();
                    w.printf("  %-15s %s%n", entry.getKey(), preview);
                }
            }
            w.println();
            w.println("Usage: /snippet save <name> <text>");
            w.println("       /snippet use <name>");
            w.println("       /snippet delete <name>");
            w.flush();
            return;
        }

        String[] parts = arg.split("\\s+", 3);
        String action = parts[0];

        if ("save".equals(action) && parts.length >= 3) {
            snippets.put(parts[1], parts[2]);
            saveSnippets();
            w.println("Snippet saved: " + parts[1]);
        } else if ("use".equals(action) && parts.length >= 2) {
            String text = snippets.get(parts[1]);
            if (text != null) {
                w.println("Using snippet: " + parts[1]);
                w.println(text);
            } else {
                w.println("Snippet not found: " + parts[1]);
            }
        } else if ("delete".equals(action) && parts.length >= 2) {
            if (snippets.remove(parts[1]) != null) {
                saveSnippets();
                w.println("Snippet deleted: " + parts[1]);
            } else {
                w.println("Snippet not found: " + parts[1]);
            }
        } else {
            w.println("Usage: /snippet [save|use|delete|list] ...");
        }
        w.flush();
    }

    private void loadSnippets() {
        Path file = Path.of(session.config().dataDir(), "snippets.properties");
        if (Files.exists(file)) {
            try {
                Properties props = new Properties();
                props.load(Files.newBufferedReader(file));
                props.forEach((k, v) -> snippets.put(k.toString(), v.toString()));
            } catch (IOException e) {
                log.debug("Failed to load snippets: {}", e.getMessage());
            }
        }
    }

    private void saveSnippets() {
        Path file = Path.of(session.config().dataDir(), "snippets.properties");
        try {
            Properties props = new Properties();
            snippets.forEach(props::setProperty);
            Files.createDirectories(file.getParent());
            props.store(Files.newBufferedWriter(file), "Kairo Assistant snippets");
        } catch (IOException e) {
            log.warn("Failed to save snippets: {}", e.getMessage());
        }
    }

    private void handleWatch(String arg) {
        var w = terminal.writer();
        if ("stop".equals(arg)) {
            if (watchTimer != null) {
                watchTimer.cancel();
                watchTimer = null;
                w.println("Watch stopped.");
            } else {
                w.println("No active watch.");
            }
            w.flush();
            return;
        }

        if (arg.isEmpty()) {
            w.println("Usage: /watch <interval> <tool> [args]");
            w.println("       /watch 10s shell command=date");
            w.println("       /watch stop");
            w.flush();
            return;
        }

        String[] parts = arg.split("\\s+", 2);
        if (parts.length < 2) {
            w.println("Usage: /watch <interval> <tool> [args]");
            w.flush();
            return;
        }

        int intervalSec;
        String intervalStr = parts[0].replaceAll("[^0-9]", "");
        try {
            intervalSec = Integer.parseInt(intervalStr);
        } catch (NumberFormatException e) {
            w.println("Invalid interval. Use seconds, e.g. 10s");
            w.flush();
            return;
        }
        if (intervalSec < 1) intervalSec = 1;
        if (intervalSec > 3600) intervalSec = 3600;

        if (watchTimer != null) {
            watchTimer.cancel();
        }

        String toolArg = parts[1];
        watchTimer = new java.util.Timer("watch", true);
        w.printf("Watching every %ds: %s%n", intervalSec, toolArg);
        w.flush();

        int finalIntervalSec = intervalSec;
        watchTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    String[] tParts = toolArg.split("\\s+", 2);
                    String toolName = tParts[0];
                    Map<String, Object> input = new LinkedHashMap<>();
                    if (tParts.length > 1) {
                        for (String kv : tParts[1].split("\\s+(?=\\w+=)")) {
                            int eq = kv.indexOf('=');
                            if (eq > 0) input.put(kv.substring(0, eq), kv.substring(eq + 1));
                        }
                    }
                    var result = session.toolExecutor().execute(toolName, input).block();
                    if (result != null && terminal != null) {
                        terminal.writer().printf("%n[watch %s] %s%n", toolName, result.content());
                        terminal.writer().flush();
                    }
                } catch (Exception e) {
                    if (terminal != null) {
                        terminal.writer().printf("%n[watch error] %s%n", e.getMessage());
                        terminal.writer().flush();
                    }
                }
            }
        }, 0, finalIntervalSec * 1000L);
    }

    private void handleFile(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty()) {
            w.println("Usage: /file read <path>         Read file content");
            w.println("       /file write <path> <text>  Write text to file");
            w.println("       /file append <path> <text> Append text to file");
            w.println("       /file ls [path]            List directory contents");
            w.println("       /file info <path>          Show file info (size, modified)");
            w.flush();
            return;
        }

        String[] parts = arg.split("\\s+", 3);
        String sub = parts[0];

        switch (sub) {
            case "read" -> {
                if (parts.length < 2) { w.println("Usage: /file read <path>"); w.flush(); return; }
                try {
                    Path p = Path.of(parts[1]);
                    if (!Files.exists(p)) { w.println("File not found: " + p); w.flush(); return; }
                    if (Files.size(p) > 100_000) {
                        w.println("File too large (>" + 100_000 + " bytes). Use first 200 lines:");
                        Files.lines(p).limit(200).forEach(w::println);
                    } else {
                        w.print(Files.readString(p));
                    }
                } catch (IOException e) {
                    w.println("Error reading file: " + e.getMessage());
                }
            }
            case "write" -> {
                if (parts.length < 3) { w.println("Usage: /file write <path> <text>"); w.flush(); return; }
                try {
                    Path p = Path.of(parts[1]);
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, parts[2]);
                    w.println("Written to " + p);
                } catch (IOException e) {
                    w.println("Error writing file: " + e.getMessage());
                }
            }
            case "append" -> {
                if (parts.length < 3) { w.println("Usage: /file append <path> <text>"); w.flush(); return; }
                try {
                    Path p = Path.of(parts[1]);
                    Files.writeString(p, parts[2] + "\n", java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                    w.println("Appended to " + p);
                } catch (IOException e) {
                    w.println("Error appending: " + e.getMessage());
                }
            }
            case "ls" -> {
                Path dir = parts.length > 1 ? Path.of(parts[1]) : Path.of(".");
                try (var stream = Files.list(dir)) {
                    stream.sorted().forEach(p -> {
                        String prefix = Files.isDirectory(p) ? "[D] " : "    ";
                        w.println(prefix + p.getFileName());
                    });
                } catch (IOException e) {
                    w.println("Error listing: " + e.getMessage());
                }
            }
            case "info" -> {
                if (parts.length < 2) { w.println("Usage: /file info <path>"); w.flush(); return; }
                try {
                    Path p = Path.of(parts[1]);
                    if (!Files.exists(p)) { w.println("Not found: " + p); w.flush(); return; }
                    var attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                    w.printf("  Path: %s%n", p.toAbsolutePath());
                    w.printf("  Size: %d bytes%n", attrs.size());
                    w.printf("  Modified: %s%n", attrs.lastModifiedTime());
                    w.printf("  Type: %s%n", attrs.isDirectory() ? "directory" : "file");
                } catch (IOException e) {
                    w.println("Error: " + e.getMessage());
                }
            }
            default -> w.println("Unknown /file subcommand: " + sub);
        }
        w.flush();
    }

    private void handlePipe(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty()) {
            w.println("Usage: /pipe <tool1> [args] | <tool2> [args] ...");
            w.println("       /pipe exec <shell-command>  — run command, send output to agent");
            w.println("       /pipe clipboard             — send clipboard content to agent");
            w.println("  Chains tool outputs: result of tool1 becomes 'input' for tool2");
            w.flush();
            return;
        }

        if (arg.startsWith("exec ")) {
            String cmd = arg.substring(5).trim();
            try {
                var proc = new ProcessBuilder("sh", "-c", cmd)
                        .redirectErrorStream(true)
                        .start();
                String output = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();
                w.println("[pipe exec] Command output (" + output.length() + " chars):");
                w.println(output.length() > 2000 ? output.substring(0, 2000) + "\n...(truncated)" : output);
                w.println("[pipe exec] Sending to agent...");
                w.flush();
                Msg input = Msg.of(MsgRole.USER, "Here is the output of `" + cmd + "`:\n\n```\n" + output + "\n```\n\nPlease analyze this output.");
                Msg response = session.agent().call(input).block();
                if (response != null) {
                    w.println();
                    w.println(response.text());
                }
            } catch (Exception e) {
                w.println("[pipe exec error] " + e.getMessage());
            }
            w.flush();
            return;
        }

        if ("clipboard".equals(arg.trim())) {
            try {
                var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
                String content = (String) clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (content == null || content.isBlank()) {
                    w.println("Clipboard is empty.");
                } else {
                    w.println("[pipe clipboard] " + content.length() + " chars from clipboard");
                    w.println("[pipe clipboard] Sending to agent...");
                    w.flush();
                    Msg input = Msg.of(MsgRole.USER, "Here is text from my clipboard:\n\n```\n" + content + "\n```\n\nPlease help me with this.");
                    Msg response = session.agent().call(input).block();
                    if (response != null) {
                        w.println();
                        w.println(response.text());
                    }
                }
            } catch (Exception e) {
                w.println("[pipe clipboard error] " + e.getMessage());
            }
            w.flush();
            return;
        }

        String[] stages = arg.split("\\s*\\|\\s*");
        String lastResult = null;

        for (int i = 0; i < stages.length; i++) {
            String stage = stages[i].trim();
            String[] tokens = stage.split("\\s+", 2);
            String toolName = tokens[0];
            Map<String, Object> params = new LinkedHashMap<>();

            if (tokens.length > 1) {
                for (String kv : tokens[1].split("\\s+")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        params.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                }
            }

            if (lastResult != null) {
                params.put("input", lastResult);
            }

            w.printf("[pipe %d/%d] %s%n", i + 1, stages.length, toolName);
            try {
                var result = session.toolExecutor().execute(toolName, params).block();
                if (result == null || result.isError()) {
                    String errMsg = result != null ? result.content() : "null result";
                    w.println("[pipe error] " + errMsg);
                    w.flush();
                    return;
                }
                lastResult = result.content();
            } catch (Exception e) {
                w.println("[pipe error] " + e.getMessage());
                w.flush();
                return;
            }
        }

        if (lastResult != null) {
            w.println();
            w.println(lastResult);
        }
        w.flush();
    }

    private void handleProfile(String arg) {
        var w = terminal.writer();
        Path profileDir = Path.of(session.config().dataDir(), "profiles");

        if (arg.isEmpty() || "list".equals(arg)) {
            w.println();
            w.println("=== User Profiles ===");
            try {
                if (Files.isDirectory(profileDir)) {
                    try (var stream = Files.list(profileDir)) {
                        stream.filter(p -> p.toString().endsWith(".properties"))
                                .forEach(p -> {
                                    String name = p.getFileName().toString().replace(".properties", "");
                                    boolean active = name.equals(activeProfile);
                                    w.printf("  %s %s%n", active ? "*" : " ", name);
                                });
                    }
                } else {
                    w.println("  (no profiles)");
                }
            } catch (IOException e) {
                w.println("  Error: " + e.getMessage());
            }
            w.println();
            w.println("Usage: /profile switch <name>  Switch active profile");
            w.println("       /profile create <name>  Create new profile");
            w.println("       /profile delete <name>  Delete a profile");
            w.println("       /profile show           Show current profile settings");
            w.println();
            w.flush();
            return;
        }

        String[] parts = arg.split("\\s+", 2);
        String sub = parts[0];
        String name = parts.length > 1 ? parts[1].trim() : "";

        switch (sub) {
            case "switch" -> {
                if (name.isEmpty()) { w.println("Usage: /profile switch <name>"); w.flush(); return; }
                Path pf = profileDir.resolve(name + ".properties");
                if (!Files.exists(pf)) { w.println("Profile not found: " + name); w.flush(); return; }
                activeProfile = name;
                try {
                    var props = new java.util.Properties();
                    props.load(Files.newBufferedReader(pf));
                    if (props.containsKey("system_instructions")) {
                        Path instrFile = Path.of(session.config().dataDir(), "custom-instructions.md");
                        Files.writeString(instrFile, props.getProperty("system_instructions"));
                    }
                    w.println("Switched to profile: " + name);
                    w.println("Restart to apply custom instructions change.");
                } catch (IOException e) {
                    w.println("Error loading profile: " + e.getMessage());
                }
            }
            case "create" -> {
                if (name.isEmpty()) { w.println("Usage: /profile create <name>"); w.flush(); return; }
                try {
                    Files.createDirectories(profileDir);
                    Path pf = profileDir.resolve(name + ".properties");
                    var props = new java.util.Properties();
                    props.setProperty("name", name);
                    props.setProperty("created", java.time.Instant.now().toString());
                    props.store(Files.newBufferedWriter(pf), "Kairo profile: " + name);
                    w.println("Profile created: " + name);
                } catch (IOException e) {
                    w.println("Error creating profile: " + e.getMessage());
                }
            }
            case "delete" -> {
                if (name.isEmpty()) { w.println("Usage: /profile delete <name>"); w.flush(); return; }
                try {
                    Path pf = profileDir.resolve(name + ".properties");
                    if (Files.deleteIfExists(pf)) {
                        w.println("Profile deleted: " + name);
                        if (name.equals(activeProfile)) activeProfile = "default";
                    } else {
                        w.println("Profile not found: " + name);
                    }
                } catch (IOException e) {
                    w.println("Error: " + e.getMessage());
                }
            }
            case "show" -> {
                String target = name.isEmpty() ? activeProfile : name;
                Path pf = profileDir.resolve(target + ".properties");
                if (!Files.exists(pf)) { w.println("Profile not found: " + target); w.flush(); return; }
                try {
                    var props = new java.util.Properties();
                    props.load(Files.newBufferedReader(pf));
                    w.println("=== Profile: " + target + " ===");
                    props.forEach((k, v) -> w.printf("  %s = %s%n", k, v));
                } catch (IOException e) {
                    w.println("Error: " + e.getMessage());
                }
            }
            default -> w.println("Unknown /profile subcommand: " + sub);
        }
        w.flush();
    }

    private void toggleMultiLine() {
        multiLineMode = !multiLineMode;
        multiLineBuffer.setLength(0);
        if (multiLineMode) {
            terminal.writer().println("Multi-line mode ON. Type /// on a line by itself to send.");
        } else {
            terminal.writer().println("Multi-line mode OFF.");
        }
        terminal.writer().flush();
    }

    private void handleSystemPrompt(String arg) {
        var w = terminal.writer();
        Path instructionsFile = Path.of(session.config().dataDir(), "custom-instructions.md");

        if (arg.isEmpty() || "show".equals(arg)) {
            w.println();
            w.println("=== Custom Instructions ===");
            try {
                if (Files.exists(instructionsFile)) {
                    w.println(Files.readString(instructionsFile));
                } else {
                    w.println("  (none set)");
                }
            } catch (IOException e) {
                w.println("  Error reading: " + e.getMessage());
            }
            w.println();
            w.println("Use /system set <text> to add custom instructions");
            w.println("Use /system reset to clear custom instructions");
            w.println("Changes take effect on next restart.");
            w.println();
        } else if (arg.startsWith("set ")) {
            String text = arg.substring(4).trim();
            if (text.isEmpty()) {
                w.println("Usage: /system set <instructions>");
                w.flush();
                return;
            }
            try {
                Files.createDirectories(instructionsFile.getParent());
                Files.writeString(instructionsFile, text);
                w.println("Custom instructions saved. Restart to apply.");
            } catch (IOException e) {
                w.println("Failed to save: " + e.getMessage());
            }
        } else if ("reset".equals(arg)) {
            try {
                if (Files.deleteIfExists(instructionsFile)) {
                    w.println("Custom instructions cleared. Restart to apply.");
                } else {
                    w.println("No custom instructions to clear.");
                }
            } catch (IOException e) {
                w.println("Failed to clear: " + e.getMessage());
            }
        } else if ("append".equals(arg.split("\\s+", 2)[0])) {
            String text = arg.substring("append".length()).trim();
            try {
                String existing = Files.exists(instructionsFile) ? Files.readString(instructionsFile) : "";
                Files.writeString(instructionsFile, existing + "\n" + text);
                w.println("Appended to custom instructions. Restart to apply.");
            } catch (IOException e) {
                w.println("Failed to append: " + e.getMessage());
            }
        } else {
            w.println("Usage: /system [show|set <text>|append <text>|reset]");
        }
        w.flush();
    }

    private void printEnv() {
        var w = terminal.writer();
        w.println();
        w.println("=== Runtime Environment ===");
        w.printf("  Java:        %s (%s)%n", System.getProperty("java.version"), System.getProperty("java.vendor"));
        w.printf("  OS:          %s %s%n", System.getProperty("os.name"), System.getProperty("os.version"));
        w.printf("  User:        %s%n", System.getProperty("user.name"));
        w.printf("  Working Dir: %s%n", System.getProperty("user.dir"));
        w.printf("  Data Dir:    %s%n", session.config().dataDir());
        w.printf("  Heap:        %dMB / %dMB%n",
                Runtime.getRuntime().totalMemory() / (1024 * 1024),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        w.println();
        w.println("=== KAIRO_* Environment Variables ===");
        boolean found = false;
        for (var entry : System.getenv().entrySet()) {
            if (entry.getKey().startsWith("KAIRO_")) {
                String val = entry.getKey().contains("KEY") || entry.getKey().contains("SECRET")
                        ? "***" : entry.getValue();
                w.printf("  %s = %s%n", entry.getKey(), val);
                found = true;
            }
        }
        if (!found) {
            w.println("  (none set)");
        }
        w.println();
        w.flush();
    }

    private void handleTimer(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty() || "toggle".equals(arg)) {
            timerEnabled = !timerEnabled;
            w.println("Timer display " + (timerEnabled ? "ON" : "OFF"));
        } else if ("stats".equals(arg)) {
            if (responseTimes.isEmpty()) {
                w.println("No response time data yet.");
            } else {
                long sum = responseTimes.stream().mapToLong(Long::longValue).sum();
                long min = responseTimes.stream().mapToLong(Long::longValue).min().orElse(0);
                long max = responseTimes.stream().mapToLong(Long::longValue).max().orElse(0);
                long avg = sum / responseTimes.size();
                w.println();
                w.println("=== Response Time Stats ===");
                w.printf("  Exchanges: %d%n", responseTimes.size());
                w.printf("  Average:   %dms%n", avg);
                w.printf("  Min:       %dms%n", min);
                w.printf("  Max:       %dms%n", max);
                w.printf("  Total:     %dms%n", sum);
                w.println();
            }
        } else if ("reset".equals(arg)) {
            responseTimes.clear();
            w.println("Timer stats reset.");
        } else {
            w.println("Usage: /timer [toggle|stats|reset]");
        }
        w.flush();
    }

    private void handleBookmark(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty() || "list".equals(arg)) {
            w.println();
            w.println("=== Bookmarks ===");
            if (bookmarks.isEmpty()) {
                w.println("  (no bookmarks)");
            } else {
                for (int i = 0; i < bookmarks.size(); i++) {
                    var bm = bookmarks.get(i);
                    String label = bm.getOrDefault("label", "");
                    String content = bm.getOrDefault("content", "");
                    String display = content.length() > 60 ? content.substring(0, 57) + "..." : content;
                    w.printf("  [%d] %s — %s%n", i, label.isEmpty() ? "(unlabeled)" : label, display);
                }
            }
            w.println();
            w.println("Usage: /bookmark add [label]   Bookmark last exchange");
            w.println("       /bookmark show <n>      Show bookmark detail");
            w.println("       /bookmark delete <n>    Delete a bookmark");
            w.println();
        } else if (arg.startsWith("add")) {
            if (history.size() < 2) {
                w.println("No exchanges to bookmark.");
                w.flush();
                return;
            }
            String label = arg.length() > 4 ? arg.substring(4).trim() : "";
            var last = history.subList(Math.max(0, history.size() - 2), history.size());
            Map<String, String> bm = new LinkedHashMap<>();
            bm.put("label", label);
            bm.put("user", last.get(0).get("content"));
            bm.put("assistant", last.get(1).get("content"));
            bm.put("content", last.get(0).get("content"));
            bm.put("timestamp", java.time.Instant.now().toString());
            bookmarks.add(bm);
            saveBookmarks();
            w.println("Bookmarked exchange #" + (bookmarks.size() - 1) + (label.isEmpty() ? "" : " (" + label + ")"));
        } else if (arg.startsWith("show ")) {
            try {
                int idx = Integer.parseInt(arg.substring(5).trim());
                if (idx < 0 || idx >= bookmarks.size()) { w.println("Invalid index."); w.flush(); return; }
                var bm = bookmarks.get(idx);
                w.println();
                w.printf("=== Bookmark %d %s ===%n", idx, bm.getOrDefault("label", ""));
                w.printf("  Saved: %s%n", bm.getOrDefault("timestamp", "?"));
                w.println("  [User] " + bm.getOrDefault("user", ""));
                w.println("  [Assistant] " + bm.getOrDefault("assistant", ""));
                w.println();
            } catch (NumberFormatException e) {
                w.println("Usage: /bookmark show <number>");
            }
        } else if (arg.startsWith("delete ")) {
            try {
                int idx = Integer.parseInt(arg.substring(7).trim());
                if (idx < 0 || idx >= bookmarks.size()) { w.println("Invalid index."); w.flush(); return; }
                bookmarks.remove(idx);
                saveBookmarks();
                w.println("Bookmark deleted.");
            } catch (NumberFormatException e) {
                w.println("Usage: /bookmark delete <number>");
            }
        } else {
            w.println("Usage: /bookmark [list|add|show|delete]");
        }
        w.flush();
    }

    private void saveBookmarks() {
        Path file = Path.of(session.config().dataDir(), "bookmarks.json");
        try {
            Files.createDirectories(file.getParent());
            var sb = new StringBuilder("[");
            for (int i = 0; i < bookmarks.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("{");
                var bm = bookmarks.get(i);
                var entries = new ArrayList<>(bm.entrySet());
                for (int j = 0; j < entries.size(); j++) {
                    if (j > 0) sb.append(",");
                    sb.append("\"").append(entries.get(j).getKey()).append("\":\"")
                            .append(entries.get(j).getValue().replace("\\", "\\\\")
                                    .replace("\"", "\\\"").replace("\n", "\\n"))
                            .append("\"");
                }
                sb.append("}");
            }
            sb.append("]");
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            // silently ignore
        }
    }

    private void handleTemplate(String arg, LineReader reader) {
        var w = terminal.writer();
        if (arg.isEmpty() || "list".equals(arg)) {
            loadTemplates();
            w.println();
            w.println("=== Prompt Templates ===");
            if (templates.isEmpty()) {
                w.println("  (no templates)");
            } else {
                templates.forEach((name, tpl) -> {
                    String display = tpl.length() > 60 ? tpl.substring(0, 57) + "..." : tpl;
                    w.printf("  %-16s %s%n", name, display);
                });
            }
            w.println();
            w.println("Usage: /template save <name> <text with {{vars}}>  Save template");
            w.println("       /template use <name>                        Fill and execute");
            w.println("       /template show <name>                       Show template");
            w.println("       /template delete <name>                     Delete template");
            w.println();
        } else if (arg.startsWith("save ")) {
            String rest = arg.substring(5).trim();
            int spaceIdx = rest.indexOf(' ');
            if (spaceIdx <= 0) { w.println("Usage: /template save <name> <text>"); w.flush(); return; }
            String name = rest.substring(0, spaceIdx);
            String tpl = rest.substring(spaceIdx + 1);
            templates.put(name, tpl);
            saveTemplates();
            w.println("Template saved: " + name);
        } else if (arg.startsWith("use ")) {
            String name = arg.substring(4).trim();
            loadTemplates();
            String tpl = templates.get(name);
            if (tpl == null) { w.println("Template not found: " + name); w.flush(); return; }

            // Find variables
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{(\\w+)\\}\\}").matcher(tpl);
            java.util.Set<String> vars = new java.util.LinkedHashSet<>();
            while (m.find()) vars.add(m.group(1));

            String filled = tpl;
            for (String var : vars) {
                try {
                    String value = reader.readLine(var + ": ");
                    filled = filled.replace("{{" + var + "}}", value);
                } catch (Exception e) {
                    w.println("Cancelled.");
                    w.flush();
                    return;
                }
            }

            w.println("Executing: " + filled);
            w.flush();
            executeWithInterrupt(filled, reader);
        } else if (arg.startsWith("show ")) {
            String name = arg.substring(5).trim();
            loadTemplates();
            String tpl = templates.get(name);
            if (tpl == null) { w.println("Template not found: " + name); w.flush(); return; }
            w.println("Template [" + name + "]:");
            w.println("  " + tpl);
        } else if (arg.startsWith("delete ")) {
            String name = arg.substring(7).trim();
            if (templates.remove(name) != null) {
                saveTemplates();
                w.println("Template deleted: " + name);
            } else {
                w.println("Template not found: " + name);
            }
        } else {
            w.println("Usage: /template [list|save|use|show|delete]");
        }
        w.flush();
    }

    private void loadTemplates() {
        Path file = Path.of(session.config().dataDir(), "templates.properties");
        if (Files.exists(file)) {
            try {
                var props = new java.util.Properties();
                props.load(Files.newBufferedReader(file));
                templates.clear();
                props.forEach((k, v) -> templates.put(k.toString(), v.toString()));
            } catch (IOException e) {
                log.debug("Failed to load templates: {}", e.getMessage());
            }
        }
    }

    private void saveTemplates() {
        Path file = Path.of(session.config().dataDir(), "templates.properties");
        try {
            Files.createDirectories(file.getParent());
            var props = new java.util.Properties();
            templates.forEach(props::setProperty);
            props.store(Files.newBufferedWriter(file), "Kairo Assistant templates");
        } catch (IOException e) {
            log.warn("Failed to save templates: {}", e.getMessage());
        }
    }

    private void printStats() {
        var w = terminal.writer();
        w.println();
        w.println("=== Usage Statistics ===");
        w.printf("  Current Session:%n");
        w.printf("    Exchanges:      %d%n", exchangeCount);
        w.printf("    Input chars:    %,d%n", totalInputChars);
        w.printf("    Output chars:   %,d%n", totalOutputChars);
        w.printf("    Bookmarks:      %d%n", bookmarks.size());
        w.printf("    Aliases:        %d%n", aliases.size());
        w.printf("    Snippets:       %d%n", snippets.size());
        w.printf("    Templates:      %d%n", templates.size());

        if (!responseTimes.isEmpty()) {
            long sum = responseTimes.stream().mapToLong(Long::longValue).sum();
            long avg = sum / responseTimes.size();
            long min = responseTimes.stream().mapToLong(Long::longValue).min().orElse(0);
            long max = responseTimes.stream().mapToLong(Long::longValue).max().orElse(0);
            w.printf("    Avg response:   %dms%n", avg);
            w.printf("    Min response:   %dms%n", min);
            w.printf("    Max response:   %dms%n", max);
        }

        w.printf("%n  Agent:%n");
        w.printf("    Tools:          %d%n", session.toolRegistry().getAll().size());
        w.printf("    Skills:         %d%n", session.skillRegistry().list().size());
        w.printf("    Plugins:        %d%n", session.pluginManager().plugins().size());

        var sessions = conversationStore.listSessions();
        w.printf("%n  History:%n");
        w.printf("    Saved sessions: %d%n", sessions.size());

        w.printf("%n  Memory:%n");
        Runtime rt = Runtime.getRuntime();
        w.printf("    Used heap:      %dMB%n", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        w.printf("    Max heap:       %dMB%n", rt.maxMemory() / (1024 * 1024));
        w.println();
        w.flush();
    }

    private void diffSessions(String arg) {
        var w = terminal.writer();
        String[] parts = arg.trim().split("\\s+");
        if (parts.length < 2) {
            w.println("Usage: /diff <session-a> <session-b>");
            w.println("  Compare message counts and content between two sessions.");
            w.flush();
            return;
        }

        var sessionA = conversationStore.loadSession(parts[0]);
        var sessionB = conversationStore.loadSession(parts[1]);

        if (sessionA.isEmpty()) { w.println("Session not found: " + parts[0]); w.flush(); return; }
        if (sessionB.isEmpty()) { w.println("Session not found: " + parts[1]); w.flush(); return; }

        w.println();
        w.printf("=== Diff: %s vs %s ===%n", parts[0], parts[1]);
        w.printf("  Session A (%s): %d messages%n", parts[0], sessionA.size());
        w.printf("  Session B (%s): %d messages%n", parts[1], sessionB.size());

        int userA = (int) sessionA.stream().filter(m -> "user".equals(m.get("role"))).count();
        int userB = (int) sessionB.stream().filter(m -> "user".equals(m.get("role"))).count();
        int assistantA = (int) sessionA.stream().filter(m -> "assistant".equals(m.get("role"))).count();
        int assistantB = (int) sessionB.stream().filter(m -> "assistant".equals(m.get("role"))).count();

        w.printf("  User messages:      %d vs %d%n", userA, userB);
        w.printf("  Assistant messages:  %d vs %d%n", assistantA, assistantB);

        long charsA = sessionA.stream().mapToLong(m -> {
            Object c = m.get("content");
            return c instanceof String s ? s.length() : 0;
        }).sum();
        long charsB = sessionB.stream().mapToLong(m -> {
            Object c = m.get("content");
            return c instanceof String s ? s.length() : 0;
        }).sum();
        w.printf("  Total chars:        %,d vs %,d%n", charsA, charsB);

        // Show first message of each
        if (!sessionA.isEmpty()) {
            Object firstA = sessionA.get(0).get("content");
            String previewA = firstA instanceof String s && s.length() > 50 ? s.substring(0, 47) + "..." : String.valueOf(firstA);
            w.printf("  First msg A:        %s%n", previewA);
        }
        if (!sessionB.isEmpty()) {
            Object firstB = sessionB.get(0).get("content");
            String previewB = firstB instanceof String s && s.length() > 50 ? s.substring(0, 47) + "..." : String.valueOf(firstB);
            w.printf("  First msg B:        %s%n", previewB);
        }
        w.println();
        w.flush();
    }

    private void handlePin(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty() || "list".equals(arg)) {
            w.println();
            w.println("=== Pinned Messages ===");
            if (pinnedMessages.isEmpty()) {
                w.println("  (none)");
            } else {
                for (int i = 0; i < pinnedMessages.size(); i++) {
                    String msg = pinnedMessages.get(i);
                    String display = msg.length() > 70 ? msg.substring(0, 67) + "..." : msg;
                    w.printf("  [%d] %s%n", i, display);
                }
            }
            w.println();
            w.println("Usage: /pin add <text>   Pin a message to context");
            w.println("       /pin last         Pin the last assistant response");
            w.println("       /pin remove <n>   Remove a pinned message");
            w.println("       /pin clear        Clear all pinned messages");
            w.println();
        } else if (arg.startsWith("add ")) {
            String text = arg.substring(4).trim();
            pinnedMessages.add(text);
            injectPinnedToAgent();
            w.println("Pinned message #" + (pinnedMessages.size() - 1));
        } else if ("last".equals(arg)) {
            if (history.size() < 2) {
                w.println("No exchanges to pin.");
                w.flush();
                return;
            }
            String lastResp = history.get(history.size() - 1).get("content");
            pinnedMessages.add(lastResp);
            injectPinnedToAgent();
            String display = lastResp.length() > 60 ? lastResp.substring(0, 57) + "..." : lastResp;
            w.println("Pinned: " + display);
        } else if (arg.startsWith("remove ")) {
            try {
                int idx = Integer.parseInt(arg.substring(7).trim());
                if (idx >= 0 && idx < pinnedMessages.size()) {
                    pinnedMessages.remove(idx);
                    injectPinnedToAgent();
                    w.println("Unpinned message #" + idx);
                } else {
                    w.println("Invalid index.");
                }
            } catch (NumberFormatException e) {
                w.println("Usage: /pin remove <number>");
            }
        } else if ("clear".equals(arg)) {
            pinnedMessages.clear();
            w.println("All pinned messages cleared.");
        } else {
            w.println("Usage: /pin [list|add|last|remove|clear]");
        }
        w.flush();
    }

    private void injectPinnedToAgent() {
        if (pinnedMessages.isEmpty()) return;
        StringBuilder sb = new StringBuilder("[Pinned context]\n");
        for (String msg : pinnedMessages) {
            sb.append("- ").append(msg).append("\n");
        }
        try {
            if (session.agent() instanceof DefaultReActAgent agent) {
                var pinMsg = Msg.of(MsgRole.USER, sb.toString());
                agent.injectMessages(List.of(pinMsg));
            }
        } catch (Exception e) {
            // silently handle if agent doesn't support injection
        }
    }

    private void handlePlayground(String arg, LineReader reader) {
        var w = terminal.writer();
        w.println();
        w.println("=== Tool Playground ===");
        w.println("  Test tools interactively. Type 'exit' to leave.");
        w.println();

        var allTools = session.toolRegistry().getAll();
        for (int i = 0; i < allTools.size(); i++) {
            w.printf("  [%d] %-20s %s%n", i, allTools.get(i).name(), allTools.get(i).description());
        }
        w.println();

        while (true) {
            String toolInput;
            try {
                toolInput = reader.readLine("playground> ").trim();
            } catch (Exception e) { break; }

            if ("exit".equals(toolInput) || toolInput.isEmpty()) break;

            String[] tokens = toolInput.split("\\s+", 2);
            String toolName = tokens[0];

            // Allow numeric index
            try {
                int idx = Integer.parseInt(toolName);
                if (idx >= 0 && idx < allTools.size()) {
                    toolName = allTools.get(idx).name();
                }
            } catch (NumberFormatException ignored) {}

            var toolDef = session.toolRegistry().get(toolName);
            if (toolDef == null) {
                w.println("Tool not found: " + toolName);
                w.flush();
                continue;
            }

            Map<String, Object> params = new LinkedHashMap<>();
            if (tokens.length > 1) {
                for (String kv : tokens[1].split("\\s+")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        params.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                }
            }

            w.println("Executing: " + toolName + " " + params);
            try {
                var result = session.toolExecutor().execute(toolName, params).block();
                if (result != null) {
                    w.printf("[%s] %s%n", result.isError() ? "ERROR" : "OK", result.content());
                }
            } catch (Exception e) {
                w.println("[ERROR] " + e.getMessage());
            }
            w.flush();
        }
        w.println("Exited playground.");
        w.flush();
    }

    private void handleTranslate(String arg, LineReader reader) {
        var w = terminal.writer();
        if (arg.isEmpty()) {
            w.println("Usage: /translate <language> [text]");
            w.println("  If no text given, translates the last assistant response.");
            w.println("  Example: /translate English");
            w.println("  Example: /translate Japanese こんにちは世界");
            w.flush();
            return;
        }

        String[] parts = arg.split("\\s+", 2);
        String targetLang = parts[0];
        String text;

        if (parts.length > 1) {
            text = parts[1];
        } else {
            if (history.size() < 2) {
                w.println("No response to translate.");
                w.flush();
                return;
            }
            text = history.get(history.size() - 1).get("content");
        }

        String prompt = "Translate the following text to " + targetLang
                + ". Only output the translation, nothing else:\n\n" + text;
        executeWithInterrupt(prompt, reader);
    }

    private void handleSummarize(LineReader reader) {
        var w = terminal.writer();
        if (history.isEmpty()) {
            w.println("No conversation to summarize.");
            w.flush();
            return;
        }

        StringBuilder context = new StringBuilder("Summarize this conversation concisely:\n\n");
        int start = Math.max(0, history.size() - 20);
        for (int i = start; i < history.size(); i++) {
            var entry = history.get(i);
            context.append(entry.get("role")).append(": ").append(entry.get("content")).append("\n\n");
        }

        executeWithInterrupt(context.toString(), reader);
    }

    private void handleTheme(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty()) {
            w.println("Current theme: " + theme);
            w.println("Available themes: default, minimal, bold, dim");
            w.println("Usage: /theme <name>");
        } else {
            switch (arg.toLowerCase()) {
                case "default" -> {
                    theme = "default";
                    w.println("Theme set to default (standard ANSI colors)");
                }
                case "minimal" -> {
                    theme = "minimal";
                    w.println("Theme set to minimal (reduced formatting)");
                }
                case "bold" -> {
                    theme = "bold";
                    w.println("Theme set to bold (enhanced emphasis)");
                }
                case "dim" -> {
                    theme = "dim";
                    w.println("Theme set to dim (muted colors)");
                }
                default -> w.println("Unknown theme: " + arg + ". Use: default, minimal, bold, dim");
            }
        }
        w.flush();
    }

    private void handleLog(String arg) {
        var w = terminal.writer();
        Path logDir = Path.of(session.config().dataDir(), "logs");

        if (!Files.isDirectory(logDir)) {
            w.println("No logs found. Log directory: " + logDir);
            w.flush();
            return;
        }

        int lines = 20;
        if (!arg.isEmpty()) {
            try { lines = Integer.parseInt(arg); } catch (NumberFormatException e) { /* keep default */ }
        }

        try (var stream = Files.list(logDir)) {
            var logFile = stream.filter(p -> p.toString().endsWith(".log"))
                    .max(java.util.Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0; }
                    }));

            if (logFile.isEmpty()) {
                w.println("No log files found in " + logDir);
            } else {
                w.println("=== Recent Logs (" + logFile.get().getFileName() + ") ===");
                var allLines = Files.readAllLines(logFile.get());
                int start = Math.max(0, allLines.size() - lines);
                for (int i = start; i < allLines.size(); i++) {
                    w.println(allLines.get(i));
                }
            }
        } catch (IOException e) {
            w.println("Error reading logs: " + e.getMessage());
        }
        w.flush();
    }

    private void handleFeedback(String arg, LineReader reader) {
        var w = terminal.writer();
        if (history.size() < 2) {
            w.println("No exchanges to rate.");
            w.flush();
            return;
        }

        var lastAssistant = history.get(history.size() - 1);
        String preview = lastAssistant.get("content");
        if (preview.length() > 80) preview = preview.substring(0, 77) + "...";
        w.println("Rating response: " + preview);

        String rating;
        try {
            rating = reader.readLine("Rating (1-5): ").trim();
        } catch (Exception e) { w.println("Cancelled."); w.flush(); return; }

        int score;
        try {
            score = Integer.parseInt(rating);
            if (score < 1 || score > 5) { w.println("Rating must be 1-5."); w.flush(); return; }
        } catch (NumberFormatException e) { w.println("Invalid rating."); w.flush(); return; }

        String comment = "";
        try {
            comment = reader.readLine("Comment (optional): ").trim();
        } catch (Exception e) {
            log.debug("Failed to read feedback comment: {}", e.getMessage());
        }

        Path feedbackFile = Path.of(session.config().dataDir(), "feedback.jsonl");
        try {
            Files.createDirectories(feedbackFile.getParent());
            String entry = String.format("{\"timestamp\":\"%s\",\"score\":%d,\"comment\":\"%s\",\"response\":\"%s\"}%n",
                    java.time.Instant.now(),
                    score,
                    comment.replace("\"", "\\\""),
                    lastAssistant.get("content").replace("\"", "\\\"").replace("\n", "\\n"));
            Files.writeString(feedbackFile, entry, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            w.println("Feedback saved! (score: " + score + "/5)");
        } catch (IOException e) {
            w.println("Error saving feedback: " + e.getMessage());
        }
        w.flush();
    }

    private void handleMacro(String arg, LineReader reader) {
        var w = terminal.writer();
        if (arg.isEmpty() || "list".equals(arg)) {
            w.println();
            w.println("=== Macros ===");
            if (macros.isEmpty()) {
                w.println("  (none)");
            } else {
                macros.forEach((name, cmds) ->
                        w.printf("  %-16s %d commands%n", name, cmds.size()));
            }
            w.println();
            w.println("Usage: /macro record <name>  Start recording commands");
            w.println("       /macro stop           Stop recording");
            w.println("       /macro run <name>     Execute a saved macro");
            w.println("       /macro show <name>    Show macro commands");
            w.println("       /macro delete <name>  Delete a macro");
            w.println();
        } else if (arg.startsWith("record ")) {
            String name = arg.substring(7).trim();
            if (name.isEmpty()) { w.println("Usage: /macro record <name>"); w.flush(); return; }
            recordingMacro = new ArrayList<>();
            recordingMacroName = name;
            w.println("Recording macro: " + name + " (use /macro stop to finish)");
        } else if ("stop".equals(arg)) {
            if (recordingMacro == null) { w.println("Not recording."); w.flush(); return; }
            macros.put(recordingMacroName, new ArrayList<>(recordingMacro));
            saveMacros();
            w.println("Saved macro: " + recordingMacroName + " (" + recordingMacro.size() + " commands)");
            recordingMacro = null;
            recordingMacroName = null;
        } else if (arg.startsWith("run ")) {
            String name = arg.substring(4).trim();
            loadMacros();
            List<String> cmds = macros.get(name);
            if (cmds == null) { w.println("Macro not found: " + name); w.flush(); return; }
            w.println("Running macro: " + name + " (" + cmds.size() + " commands)");
            w.flush();
            for (String cmd : cmds) {
                w.println("> " + cmd);
                w.flush();
                if (cmd.startsWith("/")) {
                    handleSlashCommand(cmd, reader);
                } else {
                    executeWithInterrupt(cmd, reader);
                }
            }
        } else if (arg.startsWith("show ")) {
            String name = arg.substring(5).trim();
            loadMacros();
            List<String> cmds = macros.get(name);
            if (cmds == null) { w.println("Macro not found: " + name); w.flush(); return; }
            w.println("Macro [" + name + "]:");
            for (int i = 0; i < cmds.size(); i++) {
                w.printf("  %d. %s%n", i + 1, cmds.get(i));
            }
        } else if (arg.startsWith("delete ")) {
            String name = arg.substring(7).trim();
            if (macros.remove(name) != null) {
                saveMacros();
                w.println("Macro deleted: " + name);
            } else {
                w.println("Macro not found: " + name);
            }
        } else {
            w.println("Usage: /macro [list|record|stop|run|show|delete]");
        }
        w.flush();
    }

    private void loadMacros() {
        Path file = Path.of(session.config().dataDir(), "macros.properties");
        if (Files.exists(file)) {
            try {
                var props = new java.util.Properties();
                props.load(Files.newBufferedReader(file));
                macros.clear();
                props.forEach((k, v) -> {
                    String name = k.toString();
                    List<String> cmds = new ArrayList<>(List.of(v.toString().split("\\|\\|")));
                    macros.put(name, cmds);
                });
            } catch (IOException e) {
                log.debug("Failed to load macros: {}", e.getMessage());
            }
        }
    }

    private void saveMacros() {
        Path file = Path.of(session.config().dataDir(), "macros.properties");
        try {
            Files.createDirectories(file.getParent());
            var props = new java.util.Properties();
            macros.forEach((name, cmds) -> props.setProperty(name, String.join("||", cmds)));
            props.store(Files.newBufferedWriter(file), "Kairo Assistant macros");
        } catch (IOException e) {
            log.warn("Failed to save macros: {}", e.getMessage());
        }
    }

    private void handleFormat(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty()) {
            w.println("Current format: " + outputFormat);
            w.println("Available: text, json, table");
            w.println("Usage: /format <text|json|table>");
        } else {
            switch (arg.toLowerCase()) {
                case "text", "json", "table" -> {
                    outputFormat = arg.toLowerCase();
                    w.println("Output format set to: " + outputFormat);
                }
                default -> w.println("Unknown format: " + arg + ". Use: text, json, table");
            }
        }
        w.flush();
    }

    private void handleSchedule(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty() || "list".equals(arg)) {
            w.println();
            w.println("=== Scheduled Tasks ===");
            if (scheduledTasks.isEmpty()) {
                w.println("  (none)");
            } else {
                scheduledTasks.keySet().forEach(id -> w.println("  " + id));
            }
            w.println();
            w.println("Usage: /schedule <cron> <tool> [args]  Schedule recurring tool execution");
            w.println("       /schedule cancel <id>           Cancel a scheduled task");
            w.println("       /schedule cancel-all            Cancel all scheduled tasks");
            w.println("  Cron shorthand: @every <N>s|m|h");
            w.println("  Example: /schedule @every 60s shell command=date");
            w.println();
        } else if (arg.startsWith("cancel-all")) {
            scheduledTasks.values().forEach(java.util.Timer::cancel);
            int count = scheduledTasks.size();
            scheduledTasks.clear();
            w.println("Cancelled " + count + " scheduled tasks.");
        } else if (arg.startsWith("cancel ")) {
            String id = arg.substring(7).trim();
            var timer = scheduledTasks.remove(id);
            if (timer != null) {
                timer.cancel();
                w.println("Cancelled: " + id);
            } else {
                w.println("Task not found: " + id);
            }
        } else {
            String[] tokens = arg.split("\\s+", 3);
            if (tokens.length < 2) {
                w.println("Usage: /schedule <interval> <tool> [args]");
                w.flush();
                return;
            }

            long intervalMs = parseInterval(tokens[0]);
            if (intervalMs <= 0) {
                w.println("Invalid interval: " + tokens[0] + ". Use: @every 30s, @every 5m, @every 1h");
                w.flush();
                return;
            }

            String toolName = tokens[1];
            Map<String, Object> input = new LinkedHashMap<>();
            if (tokens.length > 2) {
                for (String kv : tokens[2].split("\\s+")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        input.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                }
            }

            String taskId = "sched-" + (++scheduleCounter);
            var timer = new java.util.Timer(taskId, true);
            Map<String, Object> finalInput = input;
            timer.scheduleAtFixedRate(new java.util.TimerTask() {
                @Override
                public void run() {
                    try {
                        var result = session.toolExecutor().execute(toolName, finalInput).block();
                        if (terminal != null && result != null) {
                            terminal.writer().printf("%n[%s] %s: %s%n", taskId, toolName,
                                    result.content().length() > 100
                                            ? result.content().substring(0, 97) + "..."
                                            : result.content());
                            terminal.writer().flush();
                        }
                    } catch (Exception e) {
                        if (terminal != null) {
                            terminal.writer().printf("%n[%s error] %s%n", taskId, e.getMessage());
                            terminal.writer().flush();
                        }
                    }
                }
            }, intervalMs, intervalMs);

            scheduledTasks.put(taskId, timer);
            w.printf("Scheduled: %s — %s every %s%n", taskId, toolName, tokens[0]);
        }
        w.flush();
    }

    private long parseInterval(String spec) {
        if (spec.startsWith("@every")) spec = spec.substring(6).trim();
        if (spec.endsWith("s")) {
            try { return Long.parseLong(spec.replace("s", "")) * 1000; } catch (NumberFormatException e) { return -1; }
        }
        if (spec.endsWith("m")) {
            try { return Long.parseLong(spec.replace("m", "")) * 60_000; } catch (NumberFormatException e) { return -1; }
        }
        if (spec.endsWith("h")) {
            try { return Long.parseLong(spec.replace("h", "")) * 3_600_000; } catch (NumberFormatException e) { return -1; }
        }
        try { return Long.parseLong(spec) * 1000; } catch (NumberFormatException e) { return -1; }
    }

    private void handleChain(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty()) {
            w.println("Usage: /chain <step1> ; <step2> ; ...");
            w.println("  Each step: <tool> [key=val] [$prev for previous result]");
            w.println("  Example: /chain shell command=date ; shell command=\"echo Result: $prev\"");
            w.flush();
            return;
        }

        String[] steps = arg.split("\\s*;\\s*");
        Map<String, String> results = new LinkedHashMap<>();
        String lastResult = "";

        for (int i = 0; i < steps.length; i++) {
            String step = steps[i].trim();
            String stepName = "step" + (i + 1);
            String[] tokens = step.split("\\s+", 2);
            String toolName = tokens[0];
            Map<String, Object> input = new LinkedHashMap<>();

            if (tokens.length > 1) {
                String argsStr = tokens[1];
                // Replace $prev and $stepN references
                argsStr = argsStr.replace("$prev", lastResult);
                for (var entry : results.entrySet()) {
                    argsStr = argsStr.replace("$" + entry.getKey(), entry.getValue());
                }

                for (String kv : argsStr.split("\\s+")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        input.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                }
            }

            w.printf("[chain %d/%d] %s%n", i + 1, steps.length, toolName);
            try {
                var result = session.toolExecutor().execute(toolName, input).block();
                if (result == null || result.isError()) {
                    String errMsg = result != null ? result.content() : "null result";
                    w.println("[chain error at step " + (i + 1) + "] " + errMsg);
                    w.flush();
                    return;
                }
                lastResult = result.content();
                results.put(stepName, lastResult);
            } catch (Exception e) {
                w.println("[chain error at step " + (i + 1) + "] " + e.getMessage());
                w.flush();
                return;
            }
        }

        if (!lastResult.isEmpty()) {
            w.println();
            w.println(lastResult);
        }
        w.flush();
    }

    private void searchConversations(String query) {
        var w = terminal.writer();
        if (query.isEmpty()) {
            w.println("Usage: /search <query>");
            w.flush();
            return;
        }

        var results = conversationStore.search(query);
        w.println();
        w.printf("=== Search results for \"%s\" (%d matches) ===%n", query, results.size());
        for (var r : results) {
            String sessionId = (String) r.get("sessionId");
            String role = (String) r.get("role");
            String content = (String) r.get("content");
            String display = content.length() > 80 ? content.substring(0, 77) + "..." : content;
            w.printf("  [%s] %s: %s%n", sessionId, role, display);
        }
        if (results.isEmpty()) {
            w.println("  (no matches)");
        }
        w.println();
        w.flush();
    }

    private void printBanner() {
        var w = terminal.writer();
        w.println();
        w.println("  Kairo Assistant v0.1.0");
        w.println("  Built on Kairo Agent Framework");
        w.println("  Type /help for commands, /quit to exit");
        w.println();
        w.flush();
    }

    private void handleWhoami() {
        var w = terminal.writer();
        var config = session.config();
        w.println();
        w.println("Profile:   " + (activeProfile != null ? activeProfile : "default"));
        w.println("Provider:  " + config.modelProvider());
        w.println("Model:     " + config.modelName());
        w.println("Data Dir:  " + config.dataDir());
        w.println("Tools:     " + session.toolRegistry().getAll().size()
                + (focusCategory != null ? " (focus: " + focusCategory.name().toLowerCase() + ")" : ""));
        w.println("Skills:    " + session.skillRegistry().list().size());
        w.println("Plugins:   " + session.pluginManager().plugins().size());
        long uptimeSec = java.time.Duration.between(sessionStartTime, java.time.Instant.now()).toSeconds();
        w.println("Uptime:    " + formatUptime(uptimeSec));
        w.println("Session:   " + (conversationStore.currentSessionId() != null
                ? conversationStore.currentSessionId() : "not started"));
        w.println();
        w.flush();
    }

    private String formatUptime(long seconds) {
        if (seconds < 60) return seconds + "s";
        long min = seconds / 60;
        if (min < 60) return min + "m " + (seconds % 60) + "s";
        long hr = min / 60;
        return hr + "h " + (min % 60) + "m";
    }

    private void handleDoctor() {
        var w = terminal.writer();
        var config = session.config();
        w.println();
        w.println("=== Kairo Assistant Health Check ===");
        w.println();

        // API Key
        String apiKey = System.getenv("KAIRO_API_KEY");
        printCheck(w, "API Key", apiKey != null && !apiKey.isBlank(),
                "configured", "KAIRO_API_KEY not set (optional for development)");

        // Model provider
        printCheck(w, "Model Provider", config.modelProvider() != null,
                config.modelProvider(), "not configured");

        // Model name
        printCheck(w, "Model Name", config.modelName() != null,
                config.modelName(), "not configured");

        // Tools
        int toolCount = session.toolRegistry().getAll().size();
        printCheck(w, "Tools", toolCount > 0,
                toolCount + " loaded", "no tools loaded");

        // Skills
        int skillCount = session.skillRegistry().list().size();
        printCheck(w, "Skills", skillCount > 0,
                skillCount + " loaded", "no skills loaded");

        // Plugins
        int pluginCount = session.pluginManager().plugins().size();
        printCheck(w, "Plugins", true,
                pluginCount + " loaded", "");

        // Data directory
        java.nio.file.Path dataPath = java.nio.file.Path.of(config.dataDir());
        boolean dataDirOk = java.nio.file.Files.isDirectory(dataPath)
                && java.nio.file.Files.isWritable(dataPath);
        printCheck(w, "Data Dir", dataDirOk,
                "writable (" + config.dataDir() + ")", "not writable: " + config.dataDir());

        // Memory
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        int usedPct = (int) (100.0 * usedMB / maxMB);
        printCheck(w, "Memory", usedPct < 90,
                usedMB + "/" + maxMB + " MB (" + usedPct + "%)",
                "HIGH: " + usedMB + "/" + maxMB + " MB (" + usedPct + "%)");

        // Conversation store
        String sessionId = conversationStore.currentSessionId();
        printCheck(w, "Session", sessionId != null,
                "active: " + sessionId, "no active session");

        w.println();
        w.flush();
    }

    private void printCheck(java.io.PrintWriter w, String name, boolean ok, String okMsg, String failMsg) {
        String icon = ok ? "[32m✓[0m" : "[33m✗[0m";
        w.println("  " + icon + " " + name + ": " + (ok ? okMsg : failMsg));
    }

    private void handleRecent(String arg, org.jline.reader.LineReader reader) {
        var w = terminal.writer();
        if (recentCommands.isEmpty()) {
            w.println("No recent commands.");
            w.flush();
            return;
        }

        if (arg != null && !arg.isEmpty()) {
            try {
                int idx = Integer.parseInt(arg);
                if (idx >= 1 && idx <= recentCommands.size()) {
                    String cmd = recentCommands.get(recentCommands.size() - idx);
                    w.println("Re-executing: " + cmd);
                    w.flush();
                    if (cmd.startsWith("/")) {
                        handleSlashCommand(cmd, reader);
                    } else {
                        executeWithInterrupt(cmd, reader);
                    }
                    return;
                }
            } catch (NumberFormatException ignored) {}
            w.println("Usage: /recent [number to re-execute]");
            w.flush();
            return;
        }

        int count = Math.min(20, recentCommands.size());
        w.println();
        w.println("Recent commands (newest first):");
        for (int i = 0; i < count; i++) {
            String cmd = recentCommands.get(recentCommands.size() - 1 - i);
            String display = cmd.length() > 60 ? cmd.substring(0, 57) + "..." : cmd;
            w.printf("  %2d. %s%n", i + 1, display);
        }
        w.println();
        w.println("Use /recent <number> to re-execute.");
        w.println();
        w.flush();
    }

    private void handleCompact(org.jline.reader.LineReader reader) {
        var w = terminal.writer();
        if (session.agent() instanceof DefaultReActAgent agent) {
            var msgs = agent.conversationHistory();
            if (msgs.size() < 4) {
                w.println("Conversation too short to compact.");
                w.flush();
                return;
            }

            w.println("Compacting conversation...");
            w.flush();

            int beforeCount = msgs.size();
            int beforeChars = msgs.stream().mapToInt(m -> m.text() != null ? m.text().length() : 0).sum();

            try {
                Msg summaryRequest = Msg.of(MsgRole.USER,
                        "Summarize our entire conversation so far in a concise paragraph. "
                                + "Include key topics, decisions made, and any pending tasks. "
                                + "This summary will replace the conversation history for context continuity.");
                Msg summaryResponse = session.agent().call(summaryRequest).block();

                if (summaryResponse != null && summaryResponse.text() != null) {
                    history.clear();
                    String summary = summaryResponse.text();
                    Msg contextMsg = Msg.of(MsgRole.ASSISTANT,
                            "[Conversation Summary]\n" + summary);
                    agent.injectMessages(List.of(contextMsg));

                    int afterChars = summary.length();
                    w.println();
                    w.printf("Compacted: %d messages (%,d chars) -> 1 summary (%,d chars)%n",
                            beforeCount, beforeChars, afterChars);
                    w.printf("Saved ~%,d tokens%n", (beforeChars - afterChars) / 4);
                } else {
                    w.println("Failed to generate summary.");
                }
            } catch (Exception e) {
                w.println("Error during compaction: " + e.getMessage());
            }
        } else {
            w.println("Compact not supported with this agent type.");
        }
        w.flush();
    }

    private void handleTop() {
        var w = terminal.writer();
        var config = session.config();
        Runtime rt = Runtime.getRuntime();

        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMB = rt.totalMemory() / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        int pct = maxMB > 0 ? (int) (100.0 * usedMB / maxMB) : 0;
        long uptimeSec = java.time.Duration.between(sessionStartTime, java.time.Instant.now()).toSeconds();

        w.println();
        w.println("\033[1m=== Kairo Assistant Top ===\033[0m");
        w.printf("  Uptime:      %s%n", formatUptime(uptimeSec));
        w.printf("  Profile:     %s%n", activeProfile);
        w.println();

        // Memory bar
        int barWidth = 30;
        int filled = (int) (barWidth * pct / 100.0);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barWidth; i++) {
            bar.append(i < filled ? '#' : '.');
        }
        bar.append("]");
        String memColor = pct > 80 ? "\033[31m" : pct > 60 ? "\033[33m" : "\033[32m";
        w.printf("  Memory:      %s%s %d%%\033[0m  (%dMB / %dMB, max %dMB)%n",
                memColor, bar, pct, usedMB, totalMB, maxMB);

        // Threads
        w.printf("  Threads:     %d active%n", Thread.activeCount());

        // GC
        long gcCount = 0, gcTime = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gc.getCollectionCount();
            gcTime += gc.getCollectionTime();
        }
        w.printf("  GC:          %d collections, %dms total%n", gcCount, gcTime);

        w.println();
        w.println("\033[1mAgent\033[0m");
        w.printf("  Provider:    %s / %s%n", config.modelProvider(), config.modelName());
        w.printf("  State:       %s%n", session.agent().state());
        w.printf("  Tools:       %d%s%n", session.toolRegistry().getAll().size(),
                focusCategory != null ? " (focus: " + focusCategory.name().toLowerCase() + ")" : "");
        w.printf("  Skills:      %d%n", session.skillRegistry().list().size());
        w.printf("  Plugins:     %d%n", session.pluginManager().plugins().size());
        w.printf("  Cron tasks:  %d%n", scheduledTasks.size());

        w.println();
        w.println("\033[1mSession\033[0m");
        w.printf("  Exchanges:   %d%n", exchangeCount);
        w.printf("  Input chars: %,d%n", totalInputChars);
        w.printf("  Output chars:%,d%n", totalOutputChars);
        w.printf("  Bookmarks:   %d%n", bookmarks.size());
        w.printf("  Pins:        %d%n", pinnedMessages.size());
        w.printf("  Aliases:     %d%n", aliases.size());
        w.printf("  Macros:      %d%n", macros.size());

        if (!responseTimes.isEmpty()) {
            long sum = responseTimes.stream().mapToLong(Long::longValue).sum();
            long avg = sum / responseTimes.size();
            long min = responseTimes.stream().mapToLong(Long::longValue).min().orElse(0);
            long max = responseTimes.stream().mapToLong(Long::longValue).max().orElse(0);
            w.println();
            w.println("\033[1mPerformance\033[0m");
            w.printf("  Avg latency: %dms%n", avg);
            w.printf("  Min/Max:     %dms / %dms%n", min, max);
            w.printf("  Total time:  %,dms across %d calls%n", sum, responseTimes.size());
        }

        w.println();
        w.flush();
    }

    private void handleBenchmark() {
        var w = terminal.writer();
        w.println();
        w.println("=== Model Benchmark ===");
        w.printf("  Provider: %s%n", session.config().modelProvider());
        w.printf("  Model:    %s%n", session.config().modelName());
        w.println("  Running 3 rounds...");
        w.flush();

        String prompt = "Reply with exactly: PONG";
        long[] latencies = new long[3];
        int[] charCounts = new int[3];
        boolean allOk = true;

        for (int i = 0; i < 3; i++) {
            try {
                Msg input = Msg.of(MsgRole.USER, prompt);
                long start = System.currentTimeMillis();
                Msg response = session.agent().call(input).block();
                long elapsed = System.currentTimeMillis() - start;
                latencies[i] = elapsed;
                charCounts[i] = response != null && response.text() != null
                        ? response.text().length() : 0;
                w.printf("  Round %d: %dms (%d chars)%n", i + 1, elapsed, charCounts[i]);
                w.flush();
            } catch (Exception e) {
                w.printf("  Round %d: FAILED (%s)%n", i + 1, e.getMessage());
                allOk = false;
                w.flush();
            }
        }

        if (allOk) {
            long avg = (latencies[0] + latencies[1] + latencies[2]) / 3;
            long min = Math.min(latencies[0], Math.min(latencies[1], latencies[2]));
            long max = Math.max(latencies[0], Math.max(latencies[1], latencies[2]));
            int totalChars = charCounts[0] + charCounts[1] + charCounts[2];
            long totalMs = latencies[0] + latencies[1] + latencies[2];
            double cps = totalMs > 0 ? (totalChars * 1000.0 / totalMs) : 0;

            w.println();
            w.printf("  Avg latency: %dms%n", avg);
            w.printf("  Min/Max:     %dms / %dms%n", min, max);
            w.printf("  Throughput:  %.0f chars/s%n", cps);
        }
        w.println();
        w.flush();
    }

    private void handleAgenda() {
        var w = terminal.writer();
        var config = session.config();
        w.println();
        w.println("\033[1m=== Daily Agenda ===\033[0m");
        w.printf("  %s%n%n", java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy HH:mm")));

        // Session info
        long uptimeSec = java.time.Duration.between(sessionStartTime, java.time.Instant.now()).toSeconds();
        w.println("\033[1mSession\033[0m");
        w.printf("  Uptime:       %s%n", formatUptime(uptimeSec));
        w.printf("  Exchanges:    %d%n", exchangeCount);
        w.printf("  Profile:      %s%n", activeProfile);
        w.println();

        // Recent sessions
        var sessions = conversationStore.listSessions();
        w.println("\033[1mRecent Sessions\033[0m");
        if (sessions.isEmpty()) {
            w.println("  (no saved sessions)");
        } else {
            int show = Math.min(5, sessions.size());
            for (int i = 0; i < show; i++) {
                var s = sessions.get(sessions.size() - 1 - i);
                String id = s.getOrDefault("sessionId", "?").toString();
                String preview = s.getOrDefault("preview", "").toString();
                if (preview.length() > 50) preview = preview.substring(0, 47) + "...";
                w.printf("  %-12s %s%n", id, preview);
            }
        }
        w.println();

        // Scheduled tasks
        w.println("\033[1mScheduled Tasks\033[0m");
        if (scheduledTasks.isEmpty()) {
            w.println("  (none active)");
        } else {
            scheduledTasks.keySet().forEach(id -> w.println("  ▸ " + id));
        }
        w.println();

        // Bookmarks
        w.println("\033[1mBookmarks\033[0m");
        if (bookmarks.isEmpty()) {
            w.println("  (none saved)");
        } else {
            int show = Math.min(5, bookmarks.size());
            for (int i = bookmarks.size() - show; i < bookmarks.size(); i++) {
                var bm = bookmarks.get(i);
                String content = bm.getOrDefault("content", "");
                if (content.length() > 60) content = content.substring(0, 57) + "...";
                w.println("  ★ " + content);
            }
        }
        w.println();

        // Pinned messages
        w.println("\033[1mPinned Messages\033[0m");
        if (pinnedMessages.isEmpty()) {
            w.println("  (none pinned)");
        } else {
            for (String pin : pinnedMessages) {
                String display = pin.length() > 60 ? pin.substring(0, 57) + "..." : pin;
                w.println("  📌 " + display);
            }
        }
        w.println();

        // System health summary
        w.println("\033[1mSystem Health\033[0m");
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        int pct = maxMB > 0 ? (int) (100.0 * usedMB / maxMB) : 0;
        String memStatus = pct > 80 ? "⚠ HIGH" : "OK";
        w.printf("  Memory:   %dMB / %dMB (%d%%) %s%n", usedMB, maxMB, pct, memStatus);
        w.printf("  Tools:    %d%s%n", session.toolRegistry().getAll().size(),
                focusCategory != null ? " (focus: " + focusCategory.name().toLowerCase() + ")" : "");
        w.printf("  Model:    %s / %s%n", config.modelProvider(), config.modelName());
        w.println();
        w.flush();
    }

    private void handleFocus(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty() || "status".equals(arg)) {
            w.println();
            if (focusCategory != null) {
                long count = session.toolRegistry().getAll().stream()
                        .filter(t -> t.category() == focusCategory).count();
                w.printf("Focus: %s (%d tools)%n", focusCategory.name().toLowerCase(), count);
            } else {
                w.println("Focus: off (all tools visible)");
            }
            w.println();
            w.println("Categories:");
            for (ToolCategory cat : ToolCategory.values()) {
                long count = session.toolRegistry().getAll().stream()
                        .filter(t -> t.category() == cat).count();
                if (count > 0) {
                    String marker = cat == focusCategory ? " ◀" : "";
                    w.printf("  %-16s %d tools%s%n", cat.name().toLowerCase(), count, marker);
                }
            }
            w.println();
            w.println("Usage: /focus <category>  Set focus to a tool category");
            w.println("       /focus off         Clear focus (show all tools)");
            w.println();
            w.flush();
            return;
        }

        if ("off".equals(arg) || "all".equals(arg) || "clear".equals(arg)) {
            focusCategory = null;
            w.println("Focus cleared — all tools visible.");
            w.flush();
            return;
        }

        String normalized = arg.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (ToolCategory cat : ToolCategory.values()) {
            if (cat.name().equals(normalized)
                    || cat.name().startsWith(normalized)
                    || cat.name().toLowerCase().contains(arg.toLowerCase())) {
                focusCategory = cat;
                long count = session.toolRegistry().getAll().stream()
                        .filter(t -> t.category() == cat).count();
                w.printf("Focus set to: %s (%d tools)%n", cat.name().toLowerCase(), count);
                w.flush();
                return;
            }
        }

        w.println("Unknown category: " + arg);
        w.println("Available: " + java.util.Arrays.stream(ToolCategory.values())
                .map(c -> c.name().toLowerCase())
                .collect(java.util.stream.Collectors.joining(", ")));
        w.flush();
    }

    private void handleUsage() {
        var w = terminal.writer();
        w.println();
        w.println("=== API Usage Estimation ===");

        long uptime = java.time.Duration.between(sessionStartTime, java.time.Instant.now()).toMinutes();
        w.printf("  Session uptime:    %d min%n", uptime);
        w.printf("  Exchanges:         %d%n", exchangeCount);
        w.printf("  Input chars:       %,d%n", totalInputChars);
        w.printf("  Output chars:      %,d%n", totalOutputChars);

        int inputTokens = totalInputChars / 4;
        int outputTokens = totalOutputChars / 4;
        w.printf("  Est. input tokens: ~%,d%n", inputTokens);
        w.printf("  Est. output tokens:~%,d%n", outputTokens);
        w.println();

        String model = session.config().modelName().toLowerCase();
        double inputPricePerMTok;
        double outputPricePerMTok;
        if (model.contains("opus")) {
            inputPricePerMTok = 15.0;
            outputPricePerMTok = 75.0;
        } else if (model.contains("sonnet")) {
            inputPricePerMTok = 3.0;
            outputPricePerMTok = 15.0;
        } else if (model.contains("haiku")) {
            inputPricePerMTok = 0.25;
            outputPricePerMTok = 1.25;
        } else if (model.contains("gpt-4o")) {
            inputPricePerMTok = 2.5;
            outputPricePerMTok = 10.0;
        } else if (model.contains("gpt-4")) {
            inputPricePerMTok = 30.0;
            outputPricePerMTok = 60.0;
        } else {
            inputPricePerMTok = 3.0;
            outputPricePerMTok = 15.0;
        }

        double inputCost = (inputTokens / 1_000_000.0) * inputPricePerMTok;
        double outputCost = (outputTokens / 1_000_000.0) * outputPricePerMTok;
        double totalCost = inputCost + outputCost;

        w.printf("  Model:             %s%n", session.config().modelName());
        w.printf("  Pricing:           $%.2f/MTok in, $%.2f/MTok out%n", inputPricePerMTok, outputPricePerMTok);
        w.printf("  Est. session cost: $%.4f%n", totalCost);

        if (uptime > 0 && exchangeCount > 0) {
            double costPerHour = totalCost / (uptime / 60.0);
            double costPerDay = costPerHour * 8;
            double costPerMonth = costPerDay * 22;
            w.println();
            w.printf("  Projected (8h/day, 22d/mo):%n");
            w.printf("    Per hour:  $%.4f%n", costPerHour);
            w.printf("    Per day:   $%.2f%n", costPerDay);
            w.printf("    Per month: $%.2f%n", costPerMonth);
        }

        w.println();
        w.flush();
    }

    private void handleReplay(String arg) {
        var w = terminal.writer();
        if (arg.isEmpty() || "list".equals(arg)) {
            w.println();
            w.println("=== Saved Sessions ===");
            var sessions = conversationStore.listSessions();
            if (sessions.isEmpty()) {
                w.println("  (no saved sessions)");
            } else {
                for (int i = 0; i < Math.min(sessions.size(), 20); i++) {
                    var s = sessions.get(i);
                    String preview = s.getOrDefault("preview", "(empty)");
                    String time = s.getOrDefault("lastModified", "");
                    if (time.length() > 10) time = time.substring(0, 10);
                    w.printf("  %-20s %s  %s%n", s.get("id"), time, preview);
                }
                if (sessions.size() > 20) w.printf("  ... and %d more%n", sessions.size() - 20);
            }
            w.println();
            w.println("Usage: /replay <session-id>  — inject session into agent context");
            w.flush();
            return;
        }

        var entries = conversationStore.loadSession(arg);
        if (entries.isEmpty()) {
            w.println("Session not found: " + arg);
            w.flush();
            return;
        }

        if (!(session.agent() instanceof DefaultReActAgent agent)) {
            w.println("Replay not supported for this agent type.");
            w.flush();
            return;
        }

        int injected = 0;
        List<Msg> msgs = new ArrayList<>();
        for (var entry : entries) {
            String type = String.valueOf(entry.getOrDefault("type", ""));
            if (!"message".equals(type)) continue;
            String role = String.valueOf(entry.getOrDefault("role", ""));
            String content = String.valueOf(entry.getOrDefault("content", ""));
            if (content.isEmpty()) continue;

            MsgRole msgRole = switch (role) {
                case "user" -> MsgRole.USER;
                case "assistant" -> MsgRole.ASSISTANT;
                default -> MsgRole.SYSTEM;
            };
            msgs.add(Msg.of(msgRole, content));
            injected++;
        }

        if (msgs.isEmpty()) {
            w.println("Session has no messages to replay: " + arg);
        } else {
            agent.injectMessages(msgs);
            w.printf("Replayed %d messages from session '%s' into agent context.%n", injected, arg);
        }
        w.flush();
    }

    private void printHelp() {
        var w = terminal.writer();
        w.println();
        w.println("[1mConversation[0m");
        w.println("  /history [n]     Show recent history (default 10)");
        w.println("  /clear           Clear conversation history");
        w.println("  /retry           Re-send last user message");
        w.println("  /undo            Remove last exchange");
        w.println("  /pin [cmd]       Pin messages to context");
        w.println("  /bookmark [cmd]  Save important exchanges");
        w.println("  /translate <lang>  Translate last response");
        w.println("  /summarize       Summarize conversation");
        w.println("  /compact         Compress history into summary");
        w.println("  /recent [n]      Show/replay recent commands");
        w.println("  /feedback        Rate the last response");
        w.println();
        w.println("[1mSessions[0m");
        w.println("  /sessions        List saved conversations");
        w.println("  /resume <id>     Resume a saved session");
        w.println("  /replay [id]     Replay session into agent context");
        w.println("  /search <q>      Search conversation history");
        w.println("  /export [id] [fmt]  Export session (markdown|json)");
        w.println("  /delete <id>     Delete a saved session");
        w.println("  /diff <a> <b>    Compare two sessions");
        w.println();
        w.println("[1mTools & Execution[0m");
        w.println("  /tools [f]       List tools (optional filter)");
        w.println("  /focus [cat]     Focus on a tool category");
        w.println("  /skills          List available skills");
        w.println("  /run <tool> [args]  Run tool directly");
        w.println("  /playground      Interactive tool testing");
        w.println("  /pipe t1|t2      Chain tool outputs");
        w.println("  /chain s1;s2     Sequential tool chain");
        w.println("  /watch <int> <tool>  Periodic tool execution");
        w.println("  /schedule [cmd]  Cron-like tool scheduling");
        w.println();
        w.println("[1mAutomation[0m");
        w.println("  /alias [name] [text]  Manage aliases");
        w.println("  /snippet [cmd]   Manage reusable prompt snippets");
        w.println("  /template [cmd]  Reusable prompt templates");
        w.println("  /macro [cmd]     Record/replay command sequences");
        w.println();
        w.println("[1mSystem & Config[0m");
        w.println("  /status          Show assistant status");
        w.println("  /config          Show configuration");
        w.println("  /model           Show current model");
        w.println("  /version         Show version");
        w.println("  /permissions     Show permission settings");
        w.println("  /channels        List configured channels");
        w.println("  /plugins         List installed plugins (alias of /plugin list)");
        w.println("  /plugin <cmd>    Plugin lifecycle: install/list/enable/disable/uninstall/update");
        w.println("  /system [cmd]    Manage custom instructions");
        w.println("  /profile [cmd]   Switch user profiles");
        w.println("  /env             Show runtime environment");
        w.println();
        w.println("[1mDisplay[0m");
        w.println("  /verbose         Toggle verbose mode");
        w.println("  /render          Toggle markdown rendering");
        w.println("  /format [fmt]    Set output format (text|json|table)");
        w.println("  /theme [name]    Set color theme");
        w.println("  /multiline       Toggle multi-line input mode");
        w.println("  /notify          Toggle completion notification");
        w.println();
        w.println("[1mDiagnostics[0m");
        w.println("  /cost            Show token usage estimate");
        w.println("  /context         Show context window usage");
        w.println("  /stats           Comprehensive usage statistics");
        w.println("  /timer [cmd]     Response time tracking");
        w.println("  /log [n|level]   View agent logs");
        w.println("  /benchmark       Model latency benchmark");
        w.println("  /top             Resource monitoring snapshot");
        w.println("  /usage           API cost estimation & projection");
        w.println("  /file [cmd]      Quick file operations");
        w.println();
        w.println("[1mGeneral[0m");
        w.println("  /help            Show this help");
        w.println("  /whoami          Show identity and session info");
        w.println("  /doctor          Run diagnostic health check");
        w.println("  /agenda          Daily briefing dashboard");
        w.println("  /interrupt       Interrupt running agent");
        w.println("  /quit            Exit the assistant");
        w.println();
        w.println("Tips: Ctrl+C to interrupt | Tab to autocomplete | /tools exec to filter by category");
        w.println();
        w.flush();
    }
}
