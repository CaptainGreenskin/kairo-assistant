package io.kairo.assistant.tool;

import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

@Tool(
        name = "skill_hub",
        description =
                "Browse, search, and load skills. "
                        + "Actions: list (show available), search (by keyword), "
                        + "info (get skill details), load (load from URL).",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class SkillHubTool implements SyncTool {

    private static volatile SkillRegistry sharedRegistry;

    public static void setRegistry(SkillRegistry registry) {
        sharedRegistry = registry;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action: list, search, info, load."));
        props.put("query", new JsonSchema("string", null, null,
                "Search query (for search action)."));
        props.put("name", new JsonSchema("string", null, null,
                "Skill name (for info action)."));
        props.put("category", new JsonSchema("string", null, null,
                "Filter by category: GENERAL, CODE, DATA, etc."));
        props.put("url", new JsonSchema("string", null, null,
                "URL to load skill from (for load action)."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.defer(() -> {
            String action = (String) args.get("action");
            if (action == null || action.isBlank()) {
                return Mono.just(ToolResult.error("skill_hub", "'action' required"));
            }

            SkillRegistry registry = sharedRegistry;
            if (registry == null) {
                return Mono.just(ToolResult.error("skill_hub", "Skill registry not available"));
            }

            return switch (action) {
                case "list" -> Mono.just(listSkills(args, registry));
                case "search" -> Mono.just(searchSkills(args, registry));
                case "info" -> Mono.just(skillInfo(args, registry));
                case "load" -> loadSkill(args, registry);
                default -> Mono.just(ToolResult.error("skill_hub", "Unknown action: " + action));
            };
        });
    }

    private ToolResult listSkills(Map<String, Object> args, SkillRegistry registry) {
        String categoryStr = (String) args.get("category");
        List<SkillDefinition> skills;

        if (categoryStr != null && !categoryStr.isBlank()) {
            try {
                SkillCategory cat = SkillCategory.valueOf(categoryStr.toUpperCase());
                skills = registry.listByCategory(cat);
            } catch (IllegalArgumentException e) {
                return ToolResult.error("skill_hub", "Unknown category: " + categoryStr);
            }
        } else {
            skills = registry.list();
        }

        if (skills.isEmpty()) {
            return ToolResult.success("skill_hub", "No skills available.");
        }

        String result = skills.stream()
                .map(s -> "- " + s.name() + " (v" + s.version() + ", " + s.category() + "): "
                        + s.description())
                .collect(Collectors.joining("\n"));
        return ToolResult.success("skill_hub", "Available skills (" + skills.size() + "):\n" + result);
    }

    private ToolResult searchSkills(Map<String, Object> args, SkillRegistry registry) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("skill_hub", "'query' required for search");
        }

        String lowerQuery = query.toLowerCase();
        List<SkillDefinition> matches = registry.list().stream()
                .filter(s -> s.name().toLowerCase().contains(lowerQuery)
                        || s.description().toLowerCase().contains(lowerQuery))
                .toList();

        if (matches.isEmpty()) {
            return ToolResult.success("skill_hub", "No skills matching: " + query);
        }

        String result = matches.stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return ToolResult.success("skill_hub", "Found " + matches.size() + " skills:\n" + result);
    }

    private ToolResult skillInfo(Map<String, Object> args, SkillRegistry registry) {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) {
            return ToolResult.error("skill_hub", "'name' required for info");
        }

        return registry.get(name)
                .map(s -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Skill: ").append(s.name()).append("\n");
                    sb.append("Version: ").append(s.version()).append("\n");
                    sb.append("Category: ").append(s.category()).append("\n");
                    sb.append("Description: ").append(s.description()).append("\n");
                    if (s.instructions() != null && !s.instructions().isBlank()) {
                        sb.append("Instructions:\n").append(s.instructions()).append("\n");
                    }
                    return ToolResult.success("skill_hub", sb.toString());
                })
                .orElse(ToolResult.error("skill_hub", "Skill not found: " + name));
    }

    private Mono<ToolResult> loadSkill(Map<String, Object> args, SkillRegistry registry) {
        String url = (String) args.get("url");
        if (url == null || url.isBlank()) {
            return Mono.just(ToolResult.error("skill_hub", "'url' required for load"));
        }

        return registry.loadFromUrl(url)
                .map(skill -> ToolResult.success("skill_hub",
                        "Skill loaded: " + skill.name() + " (v" + skill.version() + ")"))
                .onErrorResume(e -> Mono.just(ToolResult.error("skill_hub",
                        "Failed to load skill: " + e.getMessage())));
    }
}
