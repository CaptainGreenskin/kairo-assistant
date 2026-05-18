package io.kairo.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

class AssistantSkillsTest {

    @Test
    void registryContainsTenBuiltinSkills() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        assertThat(registry.list()).hasSize(10);
    }

    @Test
    void dailyBriefingSkillRegistered() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        assertThat(registry.get("daily-briefing")).isPresent();
        SkillDefinition skill = registry.get("daily-briefing").get();
        assertThat(skill.description()).contains("daily briefing");
        assertThat(skill.hasInstructions()).isTrue();
        assertThat(skill.triggerConditions()).contains("/briefing");
    }

    @Test
    void codeReviewSkillInCodeCategory() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        assertThat(registry.listByCategory(SkillCategory.CODE)).hasSize(1);
        assertThat(registry.listByCategory(SkillCategory.CODE).get(0).name())
                .isEqualTo("code-review");
    }

    @Test
    void dataAnalysisSkillInDataCategory() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        assertThat(registry.listByCategory(SkillCategory.DATA)).hasSize(1);
        assertThat(registry.listByCategory(SkillCategory.DATA).get(0).name())
                .isEqualTo("data-analysis");
    }

    @Test
    void allSkillsHaveInstructions() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        for (SkillDefinition skill : registry.list()) {
            assertThat(skill.hasInstructions())
                    .as("Skill '%s' should have instructions", skill.name())
                    .isTrue();
        }
    }

    @Test
    void allSkillsHaveTriggerConditions() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        for (SkillDefinition skill : registry.list()) {
            assertThat(skill.triggerConditions())
                    .as("Skill '%s' should have triggers", skill.name())
                    .isNotEmpty();
        }
    }

    @Test
    void translateSkillExists() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        assertThat(registry.get("translate")).isPresent();
        assertThat(registry.get("translate").get().triggerConditions()).contains("/translate");
    }

    @Test
    void webResearchSkillExists() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        assertThat(registry.get("web-research")).isPresent();
        assertThat(registry.get("web-research").get().category()).isEqualTo(SkillCategory.GENERAL);
    }

    @Test
    void nonExistentSkillReturnsEmpty() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        assertThat(registry.get("non-existent-skill")).isEmpty();
    }

    @Test
    void allSkillsHaveNonBlankVersion() {
        SkillRegistry registry = AssistantSkills.createRegistry();
        for (SkillDefinition skill : registry.list()) {
            assertThat(skill.version())
                    .as("Skill '%s' should have a version", skill.name())
                    .isNotBlank();
        }
    }
}
