package io.kairo.assistant.server;

import io.kairo.api.cron.CronTask;
import io.kairo.api.cron.CronTaskOptions;
import io.kairo.assistant.agent.AssistantSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for cron task management. Mirrors the {@code /plugin} CLI shape so the web UI
 * can drive the same lifecycle ops (create / list / pause / resume / edit / trigger / delete)
 * the REPL exposes. Pairs with kairo-cron M2 + M3 SPI additions.
 */
@RestController
@RequestMapping("/api/cron")
public class CronController {

    private final AssistantSession session;
    private final EventBroadcaster broadcaster;
    private final DashboardEventPublisher dashboard;

    public CronController(
            AssistantSession session,
            EventBroadcaster broadcaster,
            DashboardEventPublisher dashboard) {
        this.session = session;
        this.broadcaster = broadcaster;
        this.dashboard = dashboard;
    }

    @GetMapping
    public Map<String, Object> list() {
        var tasks = session.cronScheduler().list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", tasks.size());
        result.put("tasks", tasks.stream().map(this::toView).toList());
        return result;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String cron = String.valueOf(body.getOrDefault("cron", ""));
        String prompt = String.valueOf(body.getOrDefault("prompt", ""));
        if (cron.isBlank() || prompt.isBlank()) {
            return Map.of("error", "cron and prompt are required");
        }
        // Build full options bundle so the web UI can surface skill / workdir / noAgent / chain.
        CronTaskOptions.Builder b =
                CronTaskOptions.builder()
                        .recurring(parseBool(body, "recurring", true))
                        .durable(parseBool(body, "durable", false));
        Object skills = body.get("skills");
        if (skills instanceof List<?> list) {
            List<String> typed = new java.util.ArrayList<>();
            for (Object s : list) typed.add(String.valueOf(s));
            b.skills(typed);
        }
        if (body.get("workdir") instanceof String w && !w.isBlank()) b.workdir(w);
        if (parseBool(body, "noAgent", false)) {
            Object script = body.get("script");
            if (!(script instanceof String s) || s.isBlank()) {
                return Map.of("error", "noAgent=true requires non-blank script");
            }
            b.noAgent(true).script(s);
        }
        if (body.get("contextFromTaskId") instanceof String c && !c.isBlank()) {
            b.contextFromTaskId(c);
        }
        CronTask task;
        try {
            task = session.cronScheduler().create(cron, prompt, b.build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("error", e.getMessage());
        }
        broadcaster.broadcast(Map.of("type", "cron_created", "id", task.id(), "cron", task.cron()));
        dashboard.cron("cron.created", task.id());
        Map<String, Object> result = new LinkedHashMap<>(toView(task));
        result.put("status", "created");
        return result;
    }

    @PutMapping("/{taskId}")
    public Map<String, Object> edit(
            @PathVariable String taskId, @RequestBody Map<String, Object> body) {
        String newCron = body.get("cron") instanceof String c && !c.isBlank() ? c : null;
        String newPrompt =
                body.get("prompt") instanceof String p && !p.isBlank() ? p : null;
        if (newCron == null && newPrompt == null) {
            return Map.of("error", "pass at least one of 'cron' or 'prompt'");
        }
        try {
            return session.cronScheduler()
                    .edit(taskId, newCron, newPrompt)
                    .map(t -> {
                        dashboard.cron("cron.edited", t.id());
                        return toView(t);
                    })
                    .orElseGet(() -> Map.of("error", "task not found", "id", taskId));
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }

    @PostMapping("/{taskId}/pause")
    public Map<String, Object> pause(@PathVariable String taskId) {
        return session.cronScheduler()
                .pause(taskId)
                .map(t -> {
                    dashboard.cron("cron.paused", t.id());
                    return toView(t);
                })
                .orElseGet(() -> Map.of("error", "task not found", "id", taskId));
    }

    @PostMapping("/{taskId}/resume")
    public Map<String, Object> resume(@PathVariable String taskId) {
        return session.cronScheduler()
                .resume(taskId)
                .map(t -> {
                    dashboard.cron("cron.resumed", t.id());
                    return toView(t);
                })
                .orElseGet(() -> Map.of("error", "task not found", "id", taskId));
    }

    @PostMapping("/{taskId}/trigger")
    public Map<String, Object> trigger(@PathVariable String taskId) {
        boolean fired = session.cronScheduler().trigger(taskId);
        if (fired) {
            dashboard.cron("cron.triggered", taskId);
        }
        return fired
                ? Map.of("status", "triggered", "id", taskId)
                : Map.of("error", "task not found", "id", taskId);
    }

    @DeleteMapping("/{taskId}")
    public Map<String, Object> delete(@PathVariable String taskId) {
        boolean deleted = session.cronScheduler().delete(taskId);
        if (deleted) {
            broadcaster.broadcast(Map.of("type", "cron_deleted", "id", taskId));
            dashboard.cron("cron.deleted", taskId);
            return Map.of("status", "deleted", "id", taskId);
        }
        return Map.of("error", "task not found", "id", taskId);
    }

    /** Project a CronTask to the JSON shape the web UI consumes. */
    private Map<String, Object> toView(CronTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("cron", t.cron());
        m.put("prompt", t.prompt());
        m.put("recurring", t.recurring());
        m.put("durable", t.durable());
        m.put("paused", t.paused());
        m.put("consecutiveFailures", t.consecutiveFailures());
        if (t.lastError() != null) m.put("lastError", t.lastError());
        m.put("createdAt", t.createdAt().toString());
        if (t.lastFiredAt() != null) m.put("lastFiredAt", t.lastFiredAt().toString());
        if (t.nextRunAt() != null) m.put("nextRunAt", t.nextRunAt().toString());
        if (!t.skills().isEmpty()) m.put("skills", t.skills());
        if (t.workdir() != null) m.put("workdir", t.workdir());
        if (t.noAgent()) m.put("noAgent", true);
        if (t.script() != null) m.put("script", t.script());
        if (t.contextFromTaskId() != null) m.put("contextFromTaskId", t.contextFromTaskId());
        return m;
    }

    private static boolean parseBool(Map<String, Object> body, String key, boolean fallback) {
        Object v = body.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
