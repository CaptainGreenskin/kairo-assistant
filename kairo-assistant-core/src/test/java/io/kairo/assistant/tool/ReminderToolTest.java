package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.cron.CronTask;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.cron.CronScheduler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReminderToolTest {

    private final StubCronScheduler scheduler = new StubCronScheduler();
    private final ReminderTool tool = new ReminderTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("cronScheduler", scheduler));

    @Test
    void createAndList() {
        ToolResult create = tool.execute(
                Map.of("action", "create", "cron", "0 9 * * *", "message", "standup"), ctx).block();
        assertThat(create.isError()).isFalse();
        assertThat(create.content()).contains("standup");

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("standup");
    }

    @Test
    void deleteReminder() {
        tool.execute(Map.of("action", "create", "cron", "0 9 * * *", "message", "temp"), ctx).block();
        String id = scheduler.tasks.get(0).id();

        ToolResult del = tool.execute(Map.of("action", "delete", "id", id), ctx).block();
        assertThat(del.isError()).isFalse();
    }

    @Test
    void createRequiresCron() {
        ToolResult r = tool.execute(Map.of("action", "create", "message", "hello"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void noSchedulerErrors() {
        ToolContext noSched = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noSched).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void createRequiresMessage() {
        ToolResult r = tool.execute(Map.of("action", "create", "cron", "0 9 * * *"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("message");
    }

    @Test
    void createWithBlankCronFails() {
        ToolResult r = tool.execute(
                Map.of("action", "create", "cron", "  ", "message", "hello"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("cron");
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "pause"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void listEmptyReturnsNoReminders() {
        ToolResult r = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("No active");
    }

    @Test
    void deleteRequiresId() {
        ToolResult r = tool.execute(Map.of("action", "delete"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("id");
    }

    @Test
    void deleteNonExistentIdFails() {
        ToolResult r = tool.execute(Map.of("action", "delete", "id", "no-such-id"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("not found");
    }

    @Test
    void createNonRecurring() {
        ToolResult r = tool.execute(
                Map.of("action", "create", "cron", "0 9 * * *", "message", "once", "recurring", false),
                ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("recurring=false");
    }

    @Test
    void inputSchemaRequiresAction() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("action");
        assertThat(schema.properties()).containsKey("cron");
        assertThat(schema.properties()).containsKey("message");
        assertThat(schema.properties()).containsKey("recurring");
        assertThat(schema.properties()).containsKey("id");
    }

    @Test
    void toolAnnotation() {
        var ann = ReminderTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("reminder");
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }

    @Test
    void emptyDepsNoScheduler() {
        ToolContext emptyDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of("action", "list"), emptyDeps).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("CronScheduler");
    }

    @Test
    void defaultActionIsList() {
        ToolResult r = tool.execute(Map.of(), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("No active");
    }

    static class StubCronScheduler implements CronScheduler {
        final List<CronTask> tasks = new ArrayList<>();

        @Override
        public CronTask create(String cron, String prompt, boolean recurring, boolean durable) {
            CronTask task = new CronTask(
                    UUID.randomUUID().toString().substring(0, 8),
                    cron, prompt, Instant.now(), null, recurring, durable);
            tasks.add(task);
            return task;
        }

        @Override
        public boolean delete(String taskId) {
            return tasks.removeIf(t -> t.id().equals(taskId));
        }

        @Override
        public List<CronTask> list() {
            return List.copyOf(tasks);
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}
    }
}
