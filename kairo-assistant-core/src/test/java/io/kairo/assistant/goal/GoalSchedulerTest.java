package io.kairo.assistant.goal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoalSchedulerTest {

    @TempDir
    Path tempDir;

    private GoalStore store;
    private List<String> firedGoals;
    private GoalScheduler scheduler;

    @BeforeEach
    void setUp() {
        store = new GoalStore(tempDir);
        firedGoals = Collections.synchronizedList(new ArrayList<>());
        scheduler = new GoalScheduler(store, (goal, prompt) -> firedGoals.add(goal.id()),
                ZoneId.systemDefault());
    }

    @Test
    void firesGoalWhenCronMatches() {
        ZonedDateTime now = ZonedDateTime.now();
        String cron = now.getMinute() + " " + now.getHour() + " * * *";

        Goal goal = new Goal("g1", "test", "do something", cron,
                "", "", Instant.now(), null, Goal.GoalStatus.ACTIVE);
        store.save(goal);

        scheduler.tick();

        assertThat(firedGoals).contains("g1");
    }

    @Test
    void doesNotFirePausedGoal() {
        ZonedDateTime now = ZonedDateTime.now();
        String cron = now.getMinute() + " " + now.getHour() + " * * *";

        Goal goal = new Goal("g2", "paused", "prompt", cron,
                "", "", Instant.now(), null, Goal.GoalStatus.PAUSED);
        store.save(goal);

        scheduler.tick();

        assertThat(firedGoals).isEmpty();
    }

    @Test
    void doesNotFireTwiceInSameMinute() {
        ZonedDateTime now = ZonedDateTime.now();
        String cron = now.getMinute() + " " + now.getHour() + " * * *";

        Goal goal = new Goal("g3", "once", "prompt", cron,
                "", "", Instant.now(), null, Goal.GoalStatus.ACTIVE);
        store.save(goal);

        scheduler.tick();
        scheduler.tick();

        assertThat(firedGoals).hasSize(1);
    }

    @Test
    void cronStarMatchesAll() {
        ZonedDateTime now = ZonedDateTime.now();
        String cron = now.getMinute() + " * * * *";

        Goal goal = new Goal("g4", "every hour", "prompt", cron,
                "", "", Instant.now(), null, Goal.GoalStatus.ACTIVE);
        store.save(goal);

        scheduler.tick();

        assertThat(firedGoals).contains("g4");
    }

    @Test
    void cronStepExpression() {
        ZonedDateTime now = ZonedDateTime.now();
        int minute = now.getMinute();
        String step = "*/" + (minute == 0 ? "1" : String.valueOf(minute));
        String cron = step + " * * * *";

        Goal goal = new Goal("g5", "step", "prompt", cron,
                "", "", Instant.now(), null, Goal.GoalStatus.ACTIVE);
        store.save(goal);

        scheduler.tick();

        assertThat(firedGoals).contains("g5");
    }
}
