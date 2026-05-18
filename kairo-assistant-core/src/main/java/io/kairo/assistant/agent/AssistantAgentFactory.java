package io.kairo.assistant.agent;

import io.kairo.api.agent.Agent;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.ToolRegistry;
import io.kairo.assistant.plugin.PluginManager;
import io.kairo.assistant.skill.AssistantSkills;
import io.kairo.core.agent.AgentBuilder;
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

        PluginManager pluginManager = new PluginManager(toolRegistry, skillRegistry, dataPath);

        Map<String, Object> toolDeps = new HashMap<>();
        toolDeps.put("memoryStore", memoryStore);
        toolDeps.put("modelProvider", modelProvider);
        toolDeps.put("modelName", config.modelName());

        String skillListing = skillRegistry.list().stream()
                .map(s -> "- **/" + s.name() + "**: " + s.description())
                .collect(Collectors.joining("\n"));
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, skillListing);

        String customInstructions = loadCustomInstructions(dataPath);
        if (customInstructions != null && !customInstructions.isBlank()) {
            systemPrompt += "\n# Custom Instructions\n" + customInstructions + "\n";
        }

        Agent agent =
                AgentBuilder.create()
                        .name("kairo-assistant")
                        .model(modelProvider)
                        .modelName(config.modelName())
                        .tools(toolRegistry)
                        .toolExecutor(toolExecutor)
                        .toolDependencies(toolDeps)
                        .systemPrompt(systemPrompt)
                        .maxIterations(config.maxIterations())
                        .timeout(config.timeout())
                        .tokenBudget(config.tokenBudget())
                        .memoryStore(memoryStore)
                        .streaming(true)
                        .withSmartContinuation()
                        .build();

        return new AssistantSession(
                agent, toolRegistry, toolExecutor, memoryStore, cronScheduler,
                skillRegistry, pluginManager, config);
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
                return (io.kairo.api.model.ModelProvider)
                        providerClass
                                .getConstructor(String.class, String.class)
                                .newInstance(config.apiKey(), config.apiBaseUrl());
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
