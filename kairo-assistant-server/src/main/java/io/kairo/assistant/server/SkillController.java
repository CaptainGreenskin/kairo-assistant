package io.kairo.assistant.server;

import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.assistant.agent.AssistantSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry registry;

    public SkillController(AssistantSession session) {
        this.registry = session.skillRegistry();
    }

    @GetMapping
    public Map<String, Object> listSkills() {
        List<Map<String, Object>> skills = registry.list().stream()
                .map(this::toSummary)
                .toList();
        return Map.of("total", skills.size(), "items", skills);
    }

    @GetMapping("/{name}")
    public Map<String, Object> getSkill(@PathVariable String name) {
        SkillDefinition skill = registry.get(name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Skill not found: " + name));
        return toDetail(skill);
    }

    @GetMapping("/categories")
    public Map<String, Object> listCategories() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SkillCategory cat : SkillCategory.values()) {
            int count = registry.listByCategory(cat).size();
            if (count > 0) {
                counts.put(cat.name().toLowerCase(), count);
            }
        }
        return Map.of("categories", counts);
    }

    @GetMapping("/categories/{category}")
    public Map<String, Object> listByCategory(@PathVariable String category) {
        SkillCategory cat;
        try {
            cat = SkillCategory.valueOf(category.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid category: " + category);
        }
        List<Map<String, Object>> skills = registry.listByCategory(cat).stream()
                .map(this::toSummary)
                .toList();
        return Map.of("category", category.toLowerCase(), "total", skills.size(), "items", skills);
    }

    private Map<String, Object> toSummary(SkillDefinition skill) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", skill.name());
        map.put("version", skill.version());
        map.put("description", skill.description());
        map.put("category", skill.category().name().toLowerCase());
        map.put("triggers", skill.triggerConditions());
        return map;
    }

    private Map<String, Object> toDetail(SkillDefinition skill) {
        Map<String, Object> map = new LinkedHashMap<>(toSummary(skill));
        map.put("hasInstructions", skill.hasInstructions());
        map.put("isConditional", skill.isConditional());
        if (skill.hasInstructions()) {
            map.put("instructions", skill.instructions());
        }
        if (skill.pathPatterns() != null) {
            map.put("pathPatterns", skill.pathPatterns());
        }
        if (skill.requiredTools() != null) {
            map.put("requiredTools", skill.requiredTools());
        }
        if (skill.platform() != null) {
            map.put("platform", skill.platform());
        }
        if (skill.hasToolRestrictions()) {
            map.put("allowedTools", skill.allowedTools());
        }
        return map;
    }
}
