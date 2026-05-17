package io.kairo.assistant.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.core.tool.permission.PermissionMode;
import io.kairo.core.tool.permission.PermissionRuleEngine;
import io.kairo.core.tool.permission.PermissionSettings;
import org.junit.jupiter.api.Test;

class AssistantPermissionsTest {

    @Test
    void defaultsUseDefaultMode() {
        PermissionSettings settings = AssistantPermissions.defaults();
        assertThat(settings.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(settings.rules()).isNotEmpty();
    }

    @Test
    void autoApproveAllUsesBypassMode() {
        PermissionSettings settings = AssistantPermissions.autoApproveAll();
        assertThat(settings.mode()).isEqualTo(PermissionMode.BYPASS);
        assertThat(settings.rules()).isEmpty();
    }

    @Test
    void strictModeUsesStrictEnum() {
        PermissionSettings settings = AssistantPermissions.strictMode();
        assertThat(settings.mode()).isEqualTo(PermissionMode.STRICT);
        assertThat(settings.rules()).isNotEmpty();
    }

    @Test
    void createRuleEngineReturnsNonNull() {
        PermissionSettings settings = AssistantPermissions.defaults();
        PermissionRuleEngine engine = AssistantPermissions.createRuleEngine(settings);
        assertThat(engine).isNotNull();
        assertThat(engine.ruleCount()).isGreaterThan(0);
    }

    @Test
    void defaultRulesAllowReadOnlyTools() {
        PermissionSettings settings = AssistantPermissions.defaults();
        PermissionRuleEngine engine = AssistantPermissions.createRuleEngine(settings);
        assertThat(engine.resolve("time", null)).isPresent();
        assertThat(engine.resolve("calculator", null)).isPresent();
        assertThat(engine.resolve("read_file", null)).isPresent();
    }
}
