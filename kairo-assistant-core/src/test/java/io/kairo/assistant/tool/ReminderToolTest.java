package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.cron.CronTask;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.cron.CronScheduler;
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
