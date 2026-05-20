package io.kairo.assistant.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.SubagentRegistry;
import io.kairo.api.mcp.McpPlugin;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.plugin.PluginManager;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolRegistry;
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
import io.kairo.core.cron.CronFireCallback;
import io.kairo.core.cron.CronScheduler;
import io.kairo.core.cron.CronTaskStore;
import io.kairo.core.cron.DefaultCronScheduler;
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

        Path dataPath = Path.of(config.dataDir());
        dataPath.toFile().mkdirs();

        MemoryStore memoryStore = new FileMemoryStore(dataPath.resolve("memory"));

        CronTaskStore cronStore = new CronTaskStore(dataPath.resolve("cron"));
        CronFireCallback cronCallback = task -> {};
        CronScheduler cronScheduler =
                new DefaultCronScheduler(cronStore, cronCallback, ZoneId.systemDefault());

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.scan("io.kairo.assistant.tool");
        if (toolRegistry.getAll().isEmpty()) {
            log.info("Classpath scan found 0 tools, falling back to explicit registration");
            registerAllTools(toolRegistry);
        }

        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());

        io.kairo.api.model.ModelProvider modelProvider = resolveModelProvider(config);

        SkillRegistry skillRegistry = AssistantSkills.createRegistry();

        PluginRuntime pluginRuntime =
                buildPluginRuntime(
                        skillRegistry,
                        dataPath,
                        modelProvider,
                        config,
                        toolRegistry,
                        toolExecutor);

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

        return new AssistantSession(
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

        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());

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
            io.kairo.core.tool.DefaultToolExecutor toolExecutor) {
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
            io.kairo.core.tool.DefaultToolExecutor toolExecutor,
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
                                    DefaultToolExecutor toolExecutor,
                                    Map<String, Object> toolDeps,
                                    String systemPrompt,
                                    MemoryStore memoryStore) {
        return buildAgent(config, modelProvider, modelName, toolRegistry, toolExecutor, toolDeps,
                systemPrompt, memoryStore, null);
    }

    private static Agent buildAgent(AssistantConfig config,
                                    io.kairo.api.model.ModelProvider modelProvider,
                                    String modelName,
                                    ToolRegistry toolRegistry,
                                    DefaultToolExecutor toolExecutor,
                                    Map<String, Object> toolDeps,
                                    String systemPrompt,
                                    MemoryStore memoryStore,
                                    Object pluginHookHandler) {
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
        if (pluginHookHandler != null) {
            builder = builder.hook(pluginHookHandler);
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

    private static io.kairo.api.model.ModelProvider resolveModelProvider(AssistantConfig config) {
        String provider = config.modelProvider();
        String className = switch (provider) {
            case "anthropic" -> "io.kairo.core.model.anthropic.AnthropicProvider";
            case "openai", "glm", "minimax", "deepseek" ->
                    "io.kairo.core.model.openai.OpenAIProvider";
            default -> "io.kairo.core.model.openai.OpenAIProvider";
        };

        try {
            Class<?> providerClass = Class.forName(className);
            if (config.apiBaseUrl() != null && !config.apiBaseUrl().isBlank()) {
                String chatPath = resolveChatCompletionsPath(config.apiBaseUrl());
                return (io.kairo.api.model.ModelProvider)
                        providerClass
                                .getConstructor(String.class, String.class, String.class)
                                .newInstance(config.apiKey(), config.apiBaseUrl(), chatPath);
            }
            return (io.kairo.api.model.ModelProvider)
                    providerClass
                            .getConstructor(String.class)
                            .newInstance(config.apiKey());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "No ModelProvider found on classpath for: " + provider, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create ModelProvider: " + provider, e);
        }
    }

    private static String resolveChatCompletionsPath(String baseUrl) {
        if (baseUrl.matches(".*/v\\d+/?$")) {
            return "/chat/completions";
        }
        return "/v1/chat/completions";
    }

    private static void registerAllTools(DefaultToolRegistry registry) {
        String[] toolClasses = {
            "BookmarkTool", "BrowserTool", "CalculatorTool", "CalendarTool",
            "CheckpointTool", "ClarifyTool", "ClipboardTool", "CodeExecuteTool",
            "ContactsTool", "CronTool", "DelegateTaskTool", "EncodeTool",
            "EnvTool", "GitTool", "HttpRequestTool", "ImageGenTool",
            "JsonTool", "ListDirectoryTool", "McpClientTool", "MemorySearchTool",
            "NoteTool", "PatchTool", "ProcessTool", "ProjectTool",
            "ReadFileTool", "ReminderTool", "ScreenshotTool", "SearchFilesTool",
            "SendMessageTool", "SessionSearchTool", "ShellTool", "SystemInfoTool",
            "TextTool", "TimeTool", "TodoTool", "UserProfileTool",
            "VisionTool", "VoiceTool", "WeatherTool", "WebFetchTool",
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
