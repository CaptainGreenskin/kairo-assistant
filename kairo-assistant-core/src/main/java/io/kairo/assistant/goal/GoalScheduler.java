package io.kairo.assistant.goal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GoalScheduler {

    private static final Logger log = LoggerFactory.getLogger(GoalScheduler.class);

    private final GoalStore store;
    private final BiConsumer<Goal, String> executor;
    private final ZoneId zoneId;
    private final ScheduledExecutorService scheduler;

    public GoalScheduler(GoalStore store, BiConsumer<Goal, String> executor, ZoneId zoneId) {
        this.store = store;
        this.executor = executor;
        this.zoneId = zoneId;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "goal-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, 30, 60, TimeUnit.SECONDS);
        log.info("Goal scheduler started (zone={})", zoneId);
    }

    public void stop() {
        scheduler.shutdownNow();
        log.info("Goal scheduler stopped");
    }

    void tick() {
        try {
            List<Goal> active = store.active();
            ZonedDateTime now = ZonedDateTime.now(zoneId);

            for (Goal goal : active) {
                if (shouldFire(goal, now)) {
                    fire(goal);
                }
            }
        } catch (Exception e) {
            log.error("Goal scheduler tick error", e);
        }
    }

    private boolean shouldFire(Goal goal, ZonedDateTime now) {
        if (goal.cron() == null || goal.cron().isBlank()) return false;

        String[] parts = goal.cron().trim().split("\\s+");
        if (parts.length != 5) return false;

        int minute = now.getMinute();
        int hour = now.getHour();
        int dom = now.getDayOfMonth();
        int month = now.getMonthValue();
        int dow = now.getDayOfWeek().getValue() % 7;

        if (!matches(parts[0], minute)) return false;
        if (!matches(parts[1], hour)) return false;
        if (!matches(parts[2], dom)) return false;
        if (!matches(parts[3], month)) return false;
        if (!matches(parts[4], dow)) return false;

        if (goal.lastRunAt() != null) {
            ZonedDateTime lastRun = goal.lastRunAt().atZone(zoneId);
            if (lastRun.getMinute() == minute
                    && lastRun.getHour() == hour
                    && lastRun.getDayOfMonth() == dom) {
                return false;
            }
        }

        return true;
    }

    private boolean matches(String field, int value) {
        if ("*".equals(field)) return true;
        if (field.startsWith("*/")) {
            int step = Integer.parseInt(field.substring(2));
            return step > 0 && value % step == 0;
        }
        if (field.contains(",")) {
            for (String part : field.split(",")) {
                if (matchesSingle(part.trim(), value)) return true;
            }
            return false;
        }
        return matchesSingle(field, value);
    }

    private boolean matchesSingle(String field, int value) {
        if (field.contains("-")) {
            String[] range = field.split("-");
            int lo = Integer.parseInt(range[0]);
            int hi = Integer.parseInt(range[1]);
            return value >= lo && value <= hi;
        }
        try {
            return Integer.parseInt(field) == value;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void fire(Goal goal) {
        log.info("Firing goal [{}]: {}", goal.id(), goal.description());
        store.update(goal.withLastRun(Instant.now()));
        try {
            executor.accept(goal, goal.prompt());
        } catch (Exception e) {
            log.error("Goal execution failed [{}]: {}", goal.id(), e.getMessage());
        }
    }
}
