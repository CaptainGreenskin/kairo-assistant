package io.kairo.assistant.goal;

import java.time.Instant;

public record Goal(
        String id,
        String description,
        String prompt,
        String cron,
        String channel,
        String target,
        Instant createdAt,
        Instant lastRunAt,
        GoalStatus status) {

    public enum GoalStatus { ACTIVE, PAUSED, COMPLETED }

    public Goal withLastRun(Instant at) {
        return new Goal(id, description, prompt, cron, channel, target, createdAt, at, status);
    }

    public Goal withStatus(GoalStatus newStatus) {
        return new Goal(id, description, prompt, cron, channel, target, createdAt, lastRunAt, newStatus);
    }
}
