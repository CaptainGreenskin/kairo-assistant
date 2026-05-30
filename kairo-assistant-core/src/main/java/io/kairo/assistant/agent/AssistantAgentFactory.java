package io.kairo.assistant.agent;

import io.kairo.core.session.AgentSessionPool;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.SubagentRegistry;
import io.kairo.api.mcp.McpPlugin;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.plugin.PluginManager;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.ToolRegistry;
import io.kairo.assistant.tool.ToolCallLogger;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.mcp.spi.DefaultMcpPlugin;
import io.kairo.plugin.ComponentRegistrar;
import io.kairo.plugin.DefaultPluginManager;
import io.kairo.plugin.DefaultPluginRegistry;
import io.kairo.plugin.DefaultSubagentRegistry;
import io.kairo.plugin.KairoComponentRegistrar;
import io.kairo.plugin.PluginEnvironment;
import io.kairo.plugin.PluginLoader;
import io.kairo.plugin.hook.HookExecutor;
import io.kairo.plugin.hook.handlers.AgentHookActionHandler;
import io.kairo.plugin.hook.handlers.McpToolHookActionHandler;
import io.kairo.plugin.hook.handlers.PromptHookActionHandler;
import io.kairo.plugin.installer.PluginCacheManager;
import io.kairo.plugin.mcp.PluginMcpRegistrar;
import io.kairo.plugin.source.GitHubSourceFetcher;
import io.kairo.plugin.source.GitSubdirSourceFetcher;
import io.kairo.plugin.source.GitUrlSourceFetcher;
import io.kairo.plugin.source.HttpDownloader;
import io.kairo.plugin.source.LocalPathSourceFetcher;
import io.kairo.plugin.source.NpmSourceFetcher;
import io.kairo.plugin.source.SourceFetcherRegistry;
import io.kairo.core.agent.AgentBuilder;
import io.kairo.core.context.CompactionThresholds;
import io.kairo.api.cron.CronFireCallback;
import io.kairo.api.cron.CronScheduler;
import io.kairo.cron.CronTaskStore;
import io.kairo.cron.DefaultCronScheduler;
import io.kairo.core.memory.FileMemoryStore;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class AssistantAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AssistantAgentFactory.class);

    private static final String SYSTEM_PROMPT_TEMPLATE =
            """
            You are Kairo Assistant, a versatile personal AI assistant powered by the Kairo framework.

            # Core Capabilities
            - **Notes & Memory**: Save notes, search memories, manage bookmarks, user profile
            - **Reminders & Calendar**: Set reminders, date calculations, cron scheduling
            - **File Operations**: Read, write, patch, search files, list directories
            - **Code Execution**: Run Python, Node.js, Bash scripts
            - **Web & Data**: Fetch URLs, browse web pages, HTTP requests, JSON processing, weather lookup
            - **Contacts & Bookmarks**: Manage personal contacts and web bookmarks
            - **Utilities**: Calculator, clipboard, shell commands, todo lists, encoding/decoding
            - **Development**: Git operations, process management, environment variables, system info
            - **Delegation**: Spawn sub-agents for independent tasks
            - **Image & Vision**: Generate images (DALL-E), analyze images (vision)
            - **Voice**: Text-to-speech and speech-to-text (OpenAI TTS/Whisper)
            - **Browser**: Browse web pages, extract content, search in pages, take screenshots
            - **Advanced**: MCP server integration, text manipulation, session search, checkpoints
            - **Communication**: Send messages through configured channels (DingTalk, Feishu, Slack)
            - **Workflow**: Define and execute multi-step workflows, project management

            # Skills (use /skill-name to activate)
            %s

            # Guidelines
            - Be concise and helpful
            - When asked to remember something, use the note or user_profile tool
            - When asked to remind about something, use the reminder tool
            - For shell commands, always confirm destructive operations
            - Use memory_search for rich queries across stored knowledge
            - Respond in the same language the user speaks
            - Use the most specific tool for the job
            - When a skill matches user input, follow its instructions precisely
            - Ask clarifying questions when the user's intent is ambiguous
            """;

    private AssistantAgentFactory() {}

    public static AssistantSession create(AssistantConfig config) {
        Objects.requireNonNull(config, "config");
        PerfTrace t = PerfTrace.start("AssistantAgentFactory.create");

        Path dataPath = Path.of(config.dataDir());
        dataPath.toFile().mkdirs();

        MemoryStore memoryStore = new FileMemoryStore(dataPath.resolve("memory"));
        t.mark("memoryStore");

        // M-F3: Bootstrap kairo-observability. Same SimpleMeterRegistry +
        // AgentMetrics pattern as kairo-code — gives the assistant runtime
        // unified Micrometer meters for kairo.agent.calls.* / agents.* without
        // locking us into any specific exporter. Failure is non-fatal so the
        // assistant still boots if Micrometer is somehow off the classpath.
        try {
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            io.kairo.core.health.AgentCallObserver.setGlobal(
                    new io.kairo.observability.AgentMetrics(registry));
        } catch (Throwable obs) {
            log.warn("Failed to wire AgentMetrics: {}", obs.getMessage());
        }

        // Late-bound CronFireCallback — the real callback needs an Agent reference (which we
        // can't build until the tool/skill stack is up). Holder lets the scheduler start
        // immediately and the callback hot-swap once everything else is wired.
        java.util.concurrent.atomic.AtomicReference<CronFireCallback> cronCallbackHolder =
                new java.util.concurrent.atomic.AtomicReference<>(task -> {});
        CronTaskStore cronStore = new CronTaskStore(dataPath.resolve("cron"));
        CronFireCallback cronCallback = task -> cronCallbackHolder.get().onFire(task);
        io.kairo.cron.CronChainContext cronChain = new io.kairo.cron.CronChainContext();
        io.kairo.cron.CronDeliveryRegistry cronDelivery =
                new io.kairo.cron.CronDeliveryRegistry()
                        .register(new io.kairo.cron.LogCronDelivery())
                        .register(new io.kairo.cron.FileCronDelivery())
                        .register(new io.kairo.cron.HttpCronDelivery())
                        .register(io.kairo.cron.HttpCronDelivery.https());
        CronScheduler cronScheduler =
                new DefaultCronScheduler(cronStore, cronCallback, ZoneId.systemDefault());
        t.mark("cronScheduler");

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.scan("io.kairo.assistant.tool");
        if (toolRegistry.getAll().isEmpty()) {
            log.info("Classpath scan found 0 tools, falling back to explicit registration");
            registerAllTools(toolRegistry);
        }
        t.mark("toolRegistry(scan+register " + toolRegistry.getAll().size() + ")");

        // Wrap with ToolCallLogger so the /api/tools/history endpoint (and the
        // Console's Tool history tab) sees real invocations. The logger is a
        // transparent ToolExecutor decorator; the wrapped DefaultToolExecutor
        // still does the actual work.
        ToolExecutor toolExecutor =
                new ToolCallLogger(
                        new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard()));

        io.kairo.api.model.ModelProvider modelProvider = resolveModelProvider(config);
        t.mark("modelProvider(" + config.modelProvider() + ")");

        SkillRegistry skillRegistry = AssistantSkills.createRegistry();
        t.mark("skillRegistry(" + skillRegistry.list().size() + ")");

        PluginRuntime pluginRuntime =
                buildPluginRuntime(
                        skillRegistry,
                        dataPath,
                        modelProvider,
                        config,
                        toolRegistry,
                        toolExecutor);
        t.mark("pluginRuntime");

        ConversationStore conversationStore = new ConversationStore(
                dataPath.resolve("conversations"));

        Map<String, Object> toolDeps = new HashMap<>();
        toolDeps.put("memoryStore", memoryStore);
        toolDeps.put("conversationStore", conversationStore);
        toolDeps.put("modelProvider", modelProvider);
        toolDeps.put("modelName", config.modelName());
        toolDeps.put("subagentRegistry", pluginRuntime.subagentRegistry());
        toolDeps.put("pluginHookCatalog", pluginRuntime.hookCatalog());

        String systemPrompt = buildSystemPrompt(skillRegistry, dataPath);
        // Append plugin-contributed output styles (if any) so the model picks up plugin-defined
        // formatting / persona directives.
        String pluginStyles = pluginRuntime.outputStyleCatalog().render();
        if (!pluginStyles.isBlank()) {
            systemPrompt = systemPrompt + "\n\n# Plugin Output Styles\n" + pluginStyles;
        }

        // Bridge plugin hooks onto the agent's lifecycle so PreToolUse/PostToolUse/SessionStart/
        // SessionEnd/Stop fire automatically via PluginHookCatalog. The audit logger captures
        // every dispatched event for forensic review.
        PluginHookBridge hookBridge =
                new PluginHookBridge(
                        pluginRuntime.hookCatalog(),
                        HookTimeoutConfig.fromEnvironment(),
                        pluginRuntime.auditLogger());

        // Re-enable plugins that were enabled in the previous session.
        try {
            pluginRuntime.enabledStore().rehydrate();
        } catch (Exception e) {
            log.warn("Failed to rehydrate persisted enabled plugins: {}", e.getMessage());
        }

        Agent agent = buildAgent(config, modelProvider, config.modelName(),
                toolRegistry, toolExecutor, toolDeps, systemPrompt, memoryStore, hookBridge);
        t.mark("buildAgent");

        // Install the real cron callback: spawn an ephemeral agent per fire (so cron prompts
        // don't pollute the live REPL conversation), honour skill/workdir/no-agent/chain, route
        // deliveries via the registry, and run everything through the prompt-injection guard.
        final String finalSystemPrompt = systemPrompt;
        AssistantConfig finalConfig = config;
        java.util.function.Supplier<Agent> ephemeralAgentSupplier =
                () -> buildAgent(
                        finalConfig,
                        modelProvider,
                        finalConfig.modelName(),
                        toolRegistry,
                        toolExecutor,
                        toolDeps,
                        finalSystemPrompt,
                        memoryStore,
                        null);
        CronFireCallback realCron =
                new AssistantCronCallback(
                        ephemeralAgentSupplier, skillRegistry, cronChain, cronDelivery);
        cronCallbackHolder.set(new PromptInjectionGuard(realCron));

        AssistantSession s = new AssistantSession(
                agent,
                toolRegistry,
                toolExecutor,
                memoryStore,
                cronScheduler,
                skillRegistry,
                pluginRuntime.pluginManager(),
                pluginRuntime.subagentRegistry(),
                pluginRuntime.mcpPlugin(),
                pluginRuntime.hookExecutor(),
                pluginRuntime.hookCatalog(),
                pluginRuntime.outputStyleCatalog(),
                pluginRuntime.enabledStore(),
                pluginRuntime.marketplaces(),
                pluginRuntime.trustStore(),
                pluginRuntime.auditLogger(),
                config);
        t.done();
        return s;
    }

    public static Agent createAgentWithModel(AssistantConfig baseConfig,
                                             String providerName,
                                             String modelName) {
        AssistantConfig modelConfig = AssistantConfig.builder()
                .modelProvider(providerName)
                .modelName(modelName)
                .apiKey(null)
                .dataDir(baseConfig.dataDir())
                .maxIterations(baseConfig.maxIterations())
                .timeout(baseConfig.timeout())
                .tokenBudget(baseConfig.tokenBudget())
                .compactionTrigger(baseConfig.compactionTrigger())
                .build();

        Path dataPath = Path.of(baseConfig.dataDir());
        MemoryStore memoryStore = new FileMemoryStore(dataPath.resolve("memory"));

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.scan("io.kairo.assistant.tool");
        if (toolRegistry.getAll().isEmpty()) {
            registerAllTools(toolRegistry);
        }

        ToolExecutor toolExecutor =
                new ToolCallLogger(
                        new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard()));

        io.kairo.api.model.ModelProvider modelProvider = resolveModelProvider(modelConfig);

        ConversationStore conversationStore = new ConversationStore(
                dataPath.resolve("conversations"));

        Map<String, Object> toolDeps = new HashMap<>();
        toolDeps.put("memoryStore", memoryStore);
        toolDeps.put("conversationStore", conversationStore);
        toolDeps.put("modelProvider", modelProvider);
        toolDeps.put("modelName", modelName);

        SkillRegistry skillRegistry = AssistantSkills.createRegistry();
        String systemPrompt = buildSystemPrompt(skillRegistry, dataPath);

        return buildAgent(modelConfig, modelProvider, modelName,
                toolRegistry, toolExecutor, toolDeps, systemPrompt, memoryStore);
    }

    /**
     * Build a standalone agent suitable for pooling under a specific session key. {@code
     * textDeltaConsumer} receives every text token from the model as it streams in, so the
     * caller can fan it out to its session subscribers (chat SSE / WebSocket / DingTalk stream)
     * without waiting for the full response. Pass {@code null} for non-streaming consumers.
     *
     * <p>This is the entrypoint used by {@code AgentSessionPool}'s per-key agent factory so
     * each pooled agent has a session-scoped delta sink wired in at construction.
     */
    public static Agent createPooledAgent(
            AssistantConfig config,
            java.util.function.Consumer<String> textDeltaConsumer) {
        Path dataPath = Path.of(config.dataDir());
        MemoryStore memoryStore = new FileMemoryStore(dataPath.resolve("memory"));

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.scan("io.kairo.assistant.tool");
        if (toolRegistry.getAll().isEmpty()) {
            registerAllTools(toolRegistry);
        }
        ToolExecutor toolExecutor =
                new ToolCallLogger(
                        new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard()));
        io.kairo.api.model.ModelProvider modelProvider = resolveModelProvider(config);

        ConversationStore conversationStore =
                new ConversationStore(dataPath.resolve("conversations"));
        Map<String, Object> toolDeps = new HashMap<>();
        toolDeps.put("memoryStore", memoryStore);
        toolDeps.put("conversationStore", conversationStore);
        toolDeps.put("modelProvider", modelProvider);
        toolDeps.put("modelName", config.modelName());

        SkillRegistry skillRegistry = AssistantSkills.createRegistry();
        String systemPrompt = buildSystemPrompt(skillRegistry, dataPath);

        return buildAgent(
                config,
                modelProvider,
                config.modelName(),
                toolRegistry,
                toolExecutor,
                toolDeps,
                systemPrompt,
                memoryStore,
                null,
                textDeltaConsumer);
    }

    /**
     * Builds the full plugin runtime, returning a bundle of the {@link PluginManager} plus the
     * supporting objects whose lifetimes must match the assistant session:
     *
     * <ul>
     *   <li>{@link McpPlugin} — production {@link DefaultMcpPlugin} that actually forks stdio
     *       subprocesses for plugin-declared {@code mcpServers}. Must be {@link McpPlugin#close()
     *       closed} on session shutdown so subprocesses die.
     *   <li>{@link SubagentRegistry} — receives every plugin's {@code agents/*.md}.
     *   <li>{@link HookExecutor} — wired with {@link PromptHookActionHandler}, {@link
     *       AgentHookActionHandler}, and {@link McpToolHookActionHandler} so plugin hooks beyond
     *       the built-in {@code command} / {@code http} actually execute.
     *   <li>{@link PluginHookCatalog} — subscribes to {@link PluginManager#events()} and indexes
     *       hooks by event name so runtime code can dispatch them.
     * </ul>
     *
     * <p>Plugins live under {@code <dataDir>/plugins/} — cache at {@code cache/}, persistent
     * per-plugin data at {@code data/}.
     */
    private static PluginRuntime buildPluginRuntime(
            SkillRegistry skillRegistry,
            Path dataPath,
            io.kairo.api.model.ModelProvider modelProvider,
            AssistantConfig config,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor) {
        Path pluginRoot = dataPath.resolve("plugins");
        Path cacheRoot = pluginRoot.resolve("cache");
        Path dataRoot = pluginRoot.resolve("data");
        try {
            Files.createDirectories(cacheRoot);
            Files.createDirectories(dataRoot);
        } catch (IOException e) {
            log.warn("Failed to create plugin directories under {}: {}", pluginRoot, e.getMessage());
        }

        PluginCacheManager cache = new PluginCacheManager(cacheRoot);
        HttpDownloader http = HttpDownloader.jdk();
        SourceFetcherRegistry fetchers =
                new SourceFetcherRegistry()
                        .register(new LocalPathSourceFetcher())
                        .register(new GitHubSourceFetcher(cache, http))
                        .register(new GitUrlSourceFetcher(cache))
                        .register(new GitSubdirSourceFetcher(cache))
                        .register(new NpmSourceFetcher(cache, http));

        // Gap 1: real MCP bridge — DefaultMcpPlugin forks subprocesses, PluginMcpRegistrar wires
        // per-plugin mcpServers onto it.
        McpPlugin mcpPlugin = new DefaultMcpPlugin();
        PluginMcpRegistrar mcpRegistrar = new PluginMcpRegistrar(mcpPlugin);

        // Gap 2: subagents — every agents/*.md ends up addressable by qualified name.
        SubagentRegistry subagentRegistry = new DefaultSubagentRegistry();

        ComponentRegistrar registrar =
                new KairoComponentRegistrar(
                        skillRegistry, mcpRegistrar, new PluginEnvironment(), subagentRegistry);

        PluginManager pluginManager =
                new DefaultPluginManager(
                        new DefaultPluginRegistry(),
                        new PluginLoader(),
                        dataRoot,
                        registrar,
                        fetchers);

        // Marketplace registry + trust store + audit logger: REPL plugin commands consume these.
        // Build audit logger early so we can pass it into the hook executor's action handlers.
        MarketplaceStore marketplacesEarly =
                new MarketplaceStore(pluginRoot.resolve("marketplaces.json"));
        PluginTrustStore trustStoreEarly = new PluginTrustStore(pluginRoot.resolve("trust.json"));
        PluginAuditLogger auditLoggerEarly =
                new PluginAuditLogger(pluginRoot.resolve("audit.ndjson"));

        // Gap 3: hook executor + per-event catalog. Handlers are reactive and reuse the assistant's
        // ModelProvider / tools / MCP plumbing so plugin hook authors see the same runtime as the
        // agent itself.
        HookExecutor hookExecutor =
                buildHookExecutor(
                        modelProvider,
                        config,
                        toolRegistry,
                        toolExecutor,
                        mcpRegistrar,
                        auditLoggerEarly);
        PluginHookCatalog hookCatalog = new PluginHookCatalog(pluginManager, hookExecutor);

        // Output-style catalog: subscribes to plugin events; render() output appended to system
        // prompt at agent build time.
        OutputStyleCatalog outputStyleCatalog = new OutputStyleCatalog(pluginManager);

        // Persisted enable state: rehydrate from disk so previously-enabled plugins come back
        // automatically on next launch.
        EnabledPluginsStore enabledStore =
                new EnabledPluginsStore(pluginRoot.resolve("enabled.json"), pluginManager);

        return new PluginRuntime(
                pluginManager,
                mcpPlugin,
                subagentRegistry,
                hookExecutor,
                hookCatalog,
                outputStyleCatalog,
                enabledStore,
                marketplacesEarly,
                trustStoreEarly,
                auditLoggerEarly);
    }

    /**
     * Builds a {@link HookExecutor} with all three non-builtin action handlers wired against the
     * assistant's real runtime:
     *
     * <ul>
     *   <li>{@code prompt} → {@link PromptHookActionHandler} hits the assistant's {@link
     *       io.kairo.api.model.ModelProvider} directly for a single-call decision.
     *   <li>{@code agent}  → {@link SubagentAgentRunner} spawns a fresh, short-lived sub-agent so
     *       the hook can reason with full tool access without polluting the user-facing session.
     *   <li>{@code mcp_tool} → {@link PluginMcpToolDispatcher} routes through any stdio MCP
     *       subprocess registered by another enabled plugin (cross-plugin tool calls).
     * </ul>
     */
    private static HookExecutor buildHookExecutor(
            io.kairo.api.model.ModelProvider modelProvider,
            AssistantConfig config,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            PluginMcpRegistrar mcpRegistrar,
            PluginAuditLogger audit) {
        ModelConfig defaultModel =
                ModelConfig.builder()
                        .model(config.modelName())
                        .systemPrompt("You are a Kairo plugin hook evaluator.")
                        .build();

        HookExecutor executor = new HookExecutor();
        executor.withHandler(new PromptHookActionHandler(modelProvider, defaultModel));
        executor.withHandler(
                new AgentHookActionHandler(
                        new SubagentAgentRunner(
                                modelProvider,
                                config.modelName(),
                                toolRegistry,
                                toolExecutor,
                                null,
                                audit)));
        executor.withHandler(
                new McpToolHookActionHandler(new PluginMcpToolDispatcher(mcpRegistrar, audit)));
        return executor;
    }

    /** Bundle returned by {@link #buildPluginRuntime} — lets the session own every lifetime. */
    record PluginRuntime(
            PluginManager pluginManager,
            McpPlugin mcpPlugin,
            SubagentRegistry subagentRegistry,
            HookExecutor hookExecutor,
            PluginHookCatalog hookCatalog,
            OutputStyleCatalog outputStyleCatalog,
            EnabledPluginsStore enabledStore,
            MarketplaceStore marketplaces,
            PluginTrustStore trustStore,
            PluginAuditLogger auditLogger) {}

    private static Agent buildAgent(AssistantConfig config,
                                    io.kairo.api.model.ModelProvider modelProvider,
                                    String modelName,
                                    ToolRegistry toolRegistry,
                                    ToolExecutor toolExecutor,
                                    Map<String, Object> toolDeps,
                                    String systemPrompt,
                                    MemoryStore memoryStore) {
        return buildAgent(config, modelProvider, modelName, toolRegistry, toolExecutor, toolDeps,
                systemPrompt, memoryStore, null, null);
    }

    private static Agent buildAgent(AssistantConfig config,
                                    io.kairo.api.model.ModelProvider modelProvider,
                                    String modelName,
                                    ToolRegistry toolRegistry,
                                    ToolExecutor toolExecutor,
                                    Map<String, Object> toolDeps,
                                    String systemPrompt,
                                    MemoryStore memoryStore,
                                    Object pluginHookHandler) {
        return buildAgent(config, modelProvider, modelName, toolRegistry, toolExecutor, toolDeps,
                systemPrompt, memoryStore, pluginHookHandler, null);
    }

    private static Agent buildAgent(AssistantConfig config,
                                    io.kairo.api.model.ModelProvider modelProvider,
                                    String modelName,
                                    ToolRegistry toolRegistry,
                                    ToolExecutor toolExecutor,
                                    Map<String, Object> toolDeps,
                                    String systemPrompt,
                                    MemoryStore memoryStore,
                                    Object pluginHookHandler,
                                    java.util.function.Consumer<String> textDeltaConsumer) {
        float trigger = config.compactionTrigger();
        CompactionThresholds compaction = CompactionThresholds.builder()
                .triggerPressure(trigger)
                .snipPressure(trigger)
                .microPressure(trigger + 0.10f)
                .collapsePressure(trigger + 0.20f)
                .autoPressure(trigger + 0.30f)
                .partialPressure(trigger + 0.40f)
                .build();

        AgentBuilder builder = AgentBuilder.create()
                .name("kairo-assistant")
                .model(modelProvider)
                .modelName(modelName)
                .tools(toolRegistry)
                .toolExecutor(toolExecutor)
                .toolDependencies(toolDeps)
                .systemPrompt(systemPrompt)
                .maxIterations(config.maxIterations())
                .timeout(config.timeout())
                .tokenBudget(config.tokenBudget())
                .memoryStore(memoryStore)
                .compactionThresholds(compaction)
                .streaming(true);
        if (textDeltaConsumer != null) {
            // Per-session token sink. With this wired, each text delta from a
            // streaming-capable model provider flows into the session router
            // immediately, so the chat UI renders tokens as they arrive
            // instead of waiting for the full response.
            builder = builder.textDeltaConsumer(textDeltaConsumer);
        }
        if (pluginHookHandler != null) {
            builder = builder.hook(pluginHookHandler);
        }

        // M-F2 + M-F5a: full guardrail chain for the personal assistant.
        //   - PiiRedactionPolicy (M-F2): redacts secrets in model + tool output
        //   - DangerousCommandPolicy (M-F5a, was assistant-self-built — now upstream): blocks
        //     rm -rf / / mkfs / shutdown etc on shell-style tools
        //   - PathTraversalPolicy (M-F5a): blocks `../` and writes to /etc/passwd etc
        //   - ToolLoopDetectionPolicy (M-F5a): warns/denies when same (tool, args) fires too
        //     often within a session
        // Set KAIRO_PII_REDACTION=off to skip the PII step (debugging only — the dangerous-
        // command and path-traversal policies stay on regardless).
        try {
            var policies = new java.util.ArrayList<io.kairo.api.guardrail.GuardrailPolicy>();
            if (!"off".equalsIgnoreCase(System.getenv("KAIRO_PII_REDACTION"))) {
                policies.add(new io.kairo.security.pii.PiiRedactionPolicy());
            }
            policies.add(new io.kairo.core.guardrail.policy.DangerousCommandPolicy());
            policies.add(new io.kairo.core.guardrail.policy.PathTraversalPolicy());
            policies.add(new io.kairo.core.guardrail.policy.ToolLoopDetectionPolicy());
            var chain = new io.kairo.core.guardrail.DefaultGuardrailChain(policies);
            builder = builder.guardrailChain(chain);
        } catch (Throwable t) {
            log.warn("Failed to wire guardrail chain: {}", t.getMessage());
        }

        return builder.build();
    }

    private static String buildSystemPrompt(SkillRegistry skillRegistry, Path dataPath) {
        String skillListing = skillRegistry.list().stream()
                .map(s -> "- **/" + s.name() + "**: " + s.description())
                .collect(Collectors.joining("\n"));
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, skillListing);

        String customInstructions = loadCustomInstructions(dataPath);
        if (customInstructions != null && !customInstructions.isBlank()) {
            systemPrompt += "\n# Custom Instructions\n" + customInstructions + "\n";
        }
        return systemPrompt;
    }

    private static String loadCustomInstructions(Path dataPath) {
        Path file = dataPath.resolve("custom-instructions.md");
        if (Files.exists(file)) {
            try {
                return Files.readString(file);
            } catch (IOException e) {
                log.warn("Failed to read custom instructions from {}: {}", file, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Build the {@link io.kairo.api.model.ModelProvider} for the configured assistant. Delegates
     * to {@link io.kairo.core.model.DefaultProviderRegistry#withBuiltIns()} so adding a new
     * provider upstream automatically becomes available here — no per-name switch to maintain.
     *
     * <p>If {@code config.apiBaseUrl()} is set we pass it as an override (handy for self-hosted
     * deployments / proxies); otherwise the registry's preset URL applies.
     */
    private static io.kairo.api.model.ModelProvider resolveModelProvider(AssistantConfig config) {
        String provider = config.modelProvider();
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException("AssistantConfig.modelProvider must not be blank");
        }
        var registry = io.kairo.core.model.DefaultProviderRegistry.withBuiltIns();
        if (!registry.isRegistered(provider)) {
            throw new IllegalStateException(
                    "Unknown provider '"
                            + provider
                            + "'. Registered: "
                            + registry.names()
                            + ". Use 'openai-compatible' with a custom apiBaseUrl for unlisted"
                            + " providers.");
        }
        var spec =
                io.kairo.api.model.ProviderSpec.of(config.apiKey(), config.apiBaseUrl())
                        .withModel(config.modelName());
        return registry.create(provider, spec);
    }

    private static void registerAllTools(DefaultToolRegistry registry) {
        String[] toolClasses = {
            "BookmarkTool", "BrowserTool", "CalculatorTool", "CalendarTool",
            "CheckpointTool", "ClarifyTool", "ClipboardTool", "CodeExecuteTool",
            "ContactsTool", "CronTool", "DelegateTaskTool", "EncodeTool", "ExpertTeamTool",
            "EnvTool", "GitTool", "HttpRequestTool", "ImageGenTool",
            "JsonTool", "ListDirectoryTool", "McpClientTool", "MemorySearchTool",
            "NoteTool", "PatchTool", "ProcessTool", "ProjectTool",
            "ReadFileTool", "ReminderTool", "ScreenshotTool", "SearchFilesTool",
            "SendMessageTool", "SessionSearchTool", "ShellTool", "SystemInfoTool",
            "TextTool", "TimeTool", "TodoTool", "UserProfileTool",
            "VisionTool", "VoiceTool", "WeatherTool", "WebFetchTool", "WebSearchTool",
            "WorkflowTool", "WriteFileTool"
        };
        for (String name : toolClasses) {
            try {
                Class<?> clazz = Class.forName("io.kairo.assistant.tool." + name);
                registry.registerTool(clazz);
            } catch (Exception e) {
                log.warn("Failed to register tool {}: {}", name, e.getMessage());
            }
        }
        log.info("Explicitly registered {} tool(s)", registry.getAll().size());
    }
}
