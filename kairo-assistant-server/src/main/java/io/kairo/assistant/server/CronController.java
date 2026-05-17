package io.kairo.assistant.server;

import io.kairo.assistant.agent.AssistantSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cron")
public class CronController {

    private final AssistantSession session;
    private final EventBroadcaster broadcaster;

    public CronController(AssistantSession session, EventBroadcaster broadcaster) {
        this.session = session;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public Map<String, Object> list() {
        var tasks = session.cronScheduler().list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", tasks.size());
        result.put("tasks", tasks.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("cron", t.cron());
            m.put("prompt", t.prompt());
            m.put("recurring", t.recurring());
            m.put("durable", t.durable());
            m.put("createdAt", t.createdAt().toString());
            if (t.lastFiredAt() != null) m.put("lastFiredAt", t.lastFiredAt().toString());
            return m;
        }).toList());
        return result;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String cron = String.valueOf(body.getOrDefault("cron", ""));
        String prompt = String.valueOf(body.getOrDefault("prompt", ""));
        if (cron.isBlank() || prompt.isBlank()) {
            return Map.of("error", "cron and prompt are required");
        }
        boolean recurring = Boolean.parseBoolean(String.valueOf(body.getOrDefault("recurring", "true")));
        boolean durable = Boolean.parseBoolean(String.valueOf(body.getOrDefault("durable", "false")));

        var task = session.cronScheduler().create(cron, prompt, recurring, durable);
        if (task == null) {
            return Map.of("error", "failed to create cron task");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "created");
        result.put("id", task.id());
        result.put("cron", task.cron());
        result.put("prompt", task.prompt());
        result.put("recurring", task.recurring());
        result.put("durable", task.durable());
        broadcaster.broadcast(Map.of("type", "cron_created", "id", task.id(), "cron", task.cron()));
        return result;
    }

    @DeleteMapping("/{taskId}")
    public Map<String, Object> delete(@PathVariable String taskId) {
        boolean deleted = session.cronScheduler().delete(taskId);
        if (deleted) {
            broadcaster.broadcast(Map.of("type", "cron_deleted", "id", taskId));
            return Map.of("status", "deleted", "id", taskId);
        }
        return Map.of("error", "task not found", "id", taskId);
    }
}
