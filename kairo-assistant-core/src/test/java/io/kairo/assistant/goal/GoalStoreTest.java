package io.kairo.assistant.goal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoalStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndRetrieve() {
        GoalStore store = new GoalStore(tempDir);
        Goal goal = new Goal("id1", "desc", "prompt", "0 9 * * *",
                "dingtalk", "conv1", Instant.now(), null, Goal.GoalStatus.ACTIVE);
        store.save(goal);

        assertThat(store.get("id1")).isPresent();
        assertThat(store.get("id1").get().description()).isEqualTo("desc");
    }

    @Test
    void persistsToFile() {
        GoalStore store1 = new GoalStore(tempDir);
        store1.save(new Goal("id2", "persistent", "p", "0 8 * * 1-5",
                "", "", Instant.now(), null, Goal.GoalStatus.ACTIVE));

        GoalStore store2 = new GoalStore(tempDir);
        assertThat(store2.get("id2")).isPresent();
    }

    @Test
    void deleteRemovesGoal() {
        GoalStore store = new GoalStore(tempDir);
        store.save(new Goal("x", "d", "p", "* * * * *",
                "", "", Instant.now(), null, Goal.GoalStatus.ACTIVE));
        assertThat(store.delete("x")).isTrue();
        assertThat(store.get("x")).isEmpty();
    }

    @Test
    void activeFiltersCorrectly() {
        GoalStore store = new GoalStore(tempDir);
        store.save(new Goal("a", "active", "p", "* * * * *",
                "", "", Instant.now(), null, Goal.GoalStatus.ACTIVE));
        store.save(new Goal("b", "paused", "p", "* * * * *",
                "", "", Instant.now(), null, Goal.GoalStatus.PAUSED));

        assertThat(store.active()).hasSize(1);
        assertThat(store.active().get(0).id()).isEqualTo("a");
    }

    @Test
    void updateModifiesExisting() {
        GoalStore store = new GoalStore(tempDir);
        Goal g = new Goal("u", "orig", "p", "0 0 * * *",
                "", "", Instant.now(), null, Goal.GoalStatus.ACTIVE);
        store.save(g);

        store.update(g.withStatus(Goal.GoalStatus.PAUSED));
        assertThat(store.get("u").get().status()).isEqualTo(Goal.GoalStatus.PAUSED);
    }
}
