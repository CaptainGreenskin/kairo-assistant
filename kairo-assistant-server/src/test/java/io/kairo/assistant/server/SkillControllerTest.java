package io.kairo.assistant.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

@SuppressWarnings("unchecked")
class SkillControllerTest {

    private SkillController controller;

    @BeforeEach
    void setUp() {
        var session = TestFixtures.defaultSession();
        controller = new SkillController(session);
    }

    @Test
    void listSkillsReturnsAll() {
        Map<String, Object> result = controller.listSkills();
        assertThat((int) result.get("total")).isEqualTo(10);
        List<Map<String, Object>> skills = (List<Map<String, Object>>) result.get("items");
        assertThat(skills).hasSize(10);
    }

    @Test
    void listSkillsContainsDailyBriefing() {
        Map<String, Object> result = controller.listSkills();
        List<Map<String, Object>> skills = (List<Map<String, Object>>) result.get("items");
        assertThat(skills).anyMatch(s -> "daily-briefing".equals(s.get("name")));
    }

    @Test
    void getSkillByName() {
        Map<String, Object> skill = controller.getSkill("daily-briefing");
        assertThat(skill.get("name")).isEqualTo("daily-briefing");
        assertThat(skill.get("version")).isEqualTo("1.0");
        assertThat(skill.get("category")).isEqualTo("general");
        assertThat((boolean) skill.get("hasInstructions")).isTrue();
        assertThat(skill.get("instructions")).isNotNull();
    }

    @Test
    void getSkillIncludesTriggers() {
        Map<String, Object> skill = controller.getSkill("translate");
        List<String> triggers = (List<String>) skill.get("triggers");
        assertThat(triggers).contains("/translate");
    }

    @Test
    void getSkillNotFoundThrows404() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSkill("nonexistent-skill"));
        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void listCategories() {
        Map<String, Object> result = controller.listCategories();
        Map<String, Integer> categories = (Map<String, Integer>) result.get("categories");
        assertThat(categories).containsKey("general");
        assertThat(categories.get("general")).isGreaterThan(0);
    }

    @Test
    void listCategoriesIncludesCodeAndData() {
        Map<String, Object> result = controller.listCategories();
        Map<String, Integer> categories = (Map<String, Integer>) result.get("categories");
        assertThat(categories).containsKey("code");
        assertThat(categories).containsKey("data");
    }

    @Test
    void listByCategory() {
        Map<String, Object> result = controller.listByCategory("general");
        assertThat(result.get("category")).isEqualTo("general");
        assertThat((int) result.get("total")).isGreaterThan(0);
        List<Map<String, Object>> skills = (List<Map<String, Object>>) result.get("items");
        assertThat(skills).allMatch(s -> "general".equals(s.get("category")));
    }

    @Test
    void listByCategoryCode() {
        Map<String, Object> result = controller.listByCategory("code");
        List<Map<String, Object>> skills = (List<Map<String, Object>>) result.get("items");
        assertThat(skills).anyMatch(s -> "code-review".equals(s.get("name")));
    }

    @Test
    void listByCategoryInvalidThrows400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listByCategory("nonexistent"));
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void listByCategoryCaseInsensitive() {
        Map<String, Object> result = controller.listByCategory("GENERAL");
        assertThat((int) result.get("total")).isGreaterThan(0);
    }

    @Test
    void skillSummaryHasExpectedFields() {
        Map<String, Object> result = controller.listSkills();
        List<Map<String, Object>> skills = (List<Map<String, Object>>) result.get("items");
        Map<String, Object> first = skills.get(0);
        assertThat(first).containsKey("name");
        assertThat(first).containsKey("version");
        assertThat(first).containsKey("description");
        assertThat(first).containsKey("category");
        assertThat(first).containsKey("triggers");
        assertThat(first).doesNotContainKey("instructions");
    }

    @Test
    void skillDetailHasInstructions() {
        Map<String, Object> skill = controller.getSkill("web-research");
        assertThat(skill).containsKey("instructions");
        assertThat(skill.get("instructions").toString()).contains("web research");
    }
}
