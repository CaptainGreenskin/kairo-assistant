package io.kairo.assistant.tool;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;

@Tool(
        name = "project",
        description =
                "Manage projects and tasks: create projects, add tasks, update status, "
                        + "list projects with progress tracking.",
        category = ToolCategory.AGENT_AND_TASK,
        sideEffect = ToolSideEffect.WRITE)
public class ProjectTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "'create' a project, 'add_task', 'update_task', 'list' projects, 'status' of a project."));
        props.put("project", new JsonSchema("string", null, null,
                "Project name."));
        props.put("task", new JsonSchema("string", null, null,
                "Task description (for add_task/update_task)."));
        props.put("task_status", new JsonSchema("string", null, null,
                "Task status: 'todo', 'in_progress', 'done' (for update_task)."));
        props.put("priority", new JsonSchema("string", null, null,
                "Priority: 'low', 'medium', 'high'. Default: 'medium'."));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args, ctx));
    }

    private ToolResult doExecute(Map<String, Object> args, ToolContext ctx) {
        String action = (String) args.get("action");
        if (action == null) {
            return ToolResult.error("project", "'action' required");
        }

        MemoryStore store = resolveMemoryStore(ctx);
        if (store == null) {
            return ToolResult.error("project", "MemoryStore not available");
        }

        return switch (action) {
            case "create" -> createProject(args, store, ctx);
            case "add_task" -> addTask(args, store, ctx);
            case "update_task" -> updateTask(args, store, ctx);
            case "list" -> listProjects(store);
            case "status" -> projectStatus(args, store);
            default -> ToolResult.error("project", "Unknown action: " + action);
        };
    }

    private ToolResult createProject(Map<String, Object> args, MemoryStore store, ToolContext ctx) {
        String name = (String) args.get("project");
        if (name == null || name.isBlank()) {
            return ToolResult.error("project", "'project' name required");
        }

        MemoryEntry entry = MemoryEntry.agent(
                UUID.randomUUID().toString(),
                ctx.agentId(),
                "PROJECT:" + name + "\nStatus: active\nCreated: " + Instant.now(),
                Set.of("project", "kanban", name.toLowerCase()));
        store.save(entry);
        return ToolResult.success("project", "Project created: " + name);
    }

    private ToolResult addTask(Map<String, Object> args, MemoryStore store, ToolContext ctx) {
        String project = (String) args.get("project");
        String task = (String) args.get("task");
        String priority = args.get("priority") instanceof String p ? p : "medium";

        if (project == null || task == null) {
            return ToolResult.error("project", "'project' and 'task' required");
        }

        MemoryEntry entry = MemoryEntry.agent(
                UUID.randomUUID().toString(),
                ctx.agentId(),
                "TASK:" + project + ":" + task + "\nPriority: " + priority + "\nStatus: todo",
                Set.of("task", "kanban", project.toLowerCase()));
        store.save(entry);
        return ToolResult.success("project",
                "Task added to " + project + ": " + task + " [" + priority + "]");
    }

    private ToolResult updateTask(Map<String, Object> args, MemoryStore store, ToolContext ctx) {
        String project = (String) args.get("project");
        String task = (String) args.get("task");
        String status = (String) args.get("task_status");

        if (project == null || task == null || status == null) {
            return ToolResult.error("project", "'project', 'task', and 'task_status' required");
        }

        List<MemoryEntry> entries = store.search("TASK:" + project + ":" + task,
                MemoryScope.AGENT, List.of("task")).collectList().block();
        if (entries == null || entries.isEmpty()) {
            return ToolResult.error("project", "Task not found: " + task + " in " + project);
        }

        MemoryEntry original = entries.get(0);
        String updatedContent = original.content().replaceAll("Status: \\w+", "Status: " + status);
        MemoryEntry updated = MemoryEntry.agent(
                UUID.randomUUID().toString(),
                ctx.agentId(),
                updatedContent,
                original.tags());
        store.delete(original.id());
        store.save(updated);
        return ToolResult.success("project",
                "Task updated: " + task + " → " + status);
    }

    private ToolResult listProjects(MemoryStore store) {
        List<MemoryEntry> projects = store.search("PROJECT:",
                MemoryScope.AGENT, List.of("project")).collectList().block();
        if (projects == null || projects.isEmpty()) {
            return ToolResult.success("project", "No projects found.");
        }

        StringBuilder sb = new StringBuilder("Projects:\n");
        for (MemoryEntry entry : projects) {
            sb.append("- ").append(entry.content().split("\n")[0].replace("PROJECT:", "")).append("\n");
        }
        return ToolResult.success("project", sb.toString());
    }

    private ToolResult projectStatus(Map<String, Object> args, MemoryStore store) {
        String project = (String) args.get("project");
        if (project == null) {
            return ToolResult.error("project", "'project' name required");
        }

        List<MemoryEntry> tasks = store.search("TASK:" + project,
                MemoryScope.AGENT, List.of("task")).collectList().block();
        if (tasks == null) tasks = List.of();

        long todo = tasks.stream().filter(t -> t.content().contains("Status: todo")).count();
        long inProgress = tasks.stream().filter(t -> t.content().contains("Status: in_progress")).count();
        long done = tasks.stream().filter(t -> t.content().contains("Status: done")).count();

        return ToolResult.success("project", String.format(
                "Project: %s\nTodo: %d | In Progress: %d | Done: %d | Total: %d",
                project, todo, inProgress, done, tasks.size()));
    }

    private MemoryStore resolveMemoryStore(ToolContext ctx) {
        if (ctx.dependencies() == null) return null;
        Object ms = ctx.dependencies().get("memoryStore");
        return ms instanceof MemoryStore store ? store : null;
    }
}
