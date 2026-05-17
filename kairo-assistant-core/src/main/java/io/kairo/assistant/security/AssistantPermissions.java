package io.kairo.assistant.security;

import io.kairo.api.tool.ToolPermission;
import io.kairo.core.tool.permission.PermissionMode;
import io.kairo.core.tool.permission.PermissionRule;
import io.kairo.core.tool.permission.PermissionRuleEngine;
import io.kairo.core.tool.permission.PermissionSettings;
import java.util.List;

public final class AssistantPermissions {

    private AssistantPermissions() {}

    public static PermissionSettings defaults() {
        return new PermissionSettings(PermissionMode.DEFAULT, defaultAllowRules());
    }

    public static PermissionSettings autoApproveAll() {
        return new PermissionSettings(PermissionMode.BYPASS, List.of());
    }

    public static PermissionSettings strictMode() {
        return new PermissionSettings(PermissionMode.STRICT, defaultAllowRules());
    }

    public static PermissionRuleEngine createRuleEngine(PermissionSettings settings) {
        return new PermissionRuleEngine(settings.rules());
    }

    private static List<PermissionRule> defaultAllowRules() {
        return List.of(
                PermissionRule.parse("time", ToolPermission.ALLOWED),
                PermissionRule.parse("weather", ToolPermission.ALLOWED),
                PermissionRule.parse("calculator", ToolPermission.ALLOWED),
                PermissionRule.parse("calendar", ToolPermission.ALLOWED),
                PermissionRule.parse("text", ToolPermission.ALLOWED),
                PermissionRule.parse("encode", ToolPermission.ALLOWED),
                PermissionRule.parse("json", ToolPermission.ALLOWED),
                PermissionRule.parse("system_info", ToolPermission.ALLOWED),
                PermissionRule.parse("env", ToolPermission.ALLOWED),
                PermissionRule.parse("clipboard", ToolPermission.ALLOWED),
                PermissionRule.parse("user_profile", ToolPermission.ALLOWED),
                PermissionRule.parse("memory_search", ToolPermission.ALLOWED),
                PermissionRule.parse("session_search", ToolPermission.ALLOWED),
                PermissionRule.parse("read_file", ToolPermission.ALLOWED),
                PermissionRule.parse("list_directory", ToolPermission.ALLOWED),
                PermissionRule.parse("search_files", ToolPermission.ALLOWED),
                PermissionRule.parse("web_fetch", ToolPermission.ALLOWED),
                PermissionRule.parse("http_request", ToolPermission.ALLOWED),
                PermissionRule.parse("note", ToolPermission.ALLOWED),
                PermissionRule.parse("todo", ToolPermission.ALLOWED),
                PermissionRule.parse("bookmark", ToolPermission.ALLOWED),
                PermissionRule.parse("contacts", ToolPermission.ALLOWED),
                PermissionRule.parse("reminder", ToolPermission.ALLOWED),
                PermissionRule.parse("git", ToolPermission.ALLOWED),
                PermissionRule.parse("process", ToolPermission.ALLOWED),
                PermissionRule.parse("cron", ToolPermission.ALLOWED),
                PermissionRule.parse("vision", ToolPermission.ALLOWED),
                PermissionRule.parse("clarify", ToolPermission.ALLOWED)
        );
    }
}
