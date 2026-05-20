package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SubagentCoordinatorTest {

    private SubagentCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new SubagentCoordinator();
    }

    @Test
    void submitAndWaitReturnsResult() {
        Agent stub = stubAgent("done");
        Msg result = coordinator.submitAndWait("s1", "test task", stub, "do something")
                .block(Duration.ofSeconds(5));
        assertThat(result).isNotNull();
        assertThat(result.text()).isEqualTo("done");
    }

    @Test
    void submitAsyncReturnsTaskId() {
        Agent stub = stubAgent("result");
        String taskId = coordinator.submit("s1", "async task", stub, "prompt");
        assertThat(taskId).isNotNull();
        assertThat(taskId).startsWith("task-");
    }

    @Test
    void asyncTaskCompletesEventually() throws InterruptedException {
        Agent stub = delayedAgent("delayed-result", 50);
        String taskId = coordinator.submit("s1", "delayed", stub, "prompt");

        Thread.sleep(200);

        SubagentCoordinator.TaskEntry entry = coordinator.getTask(taskId);
        assertThat(entry).isNotNull();
        assertThat(entry.status()).isEqualTo(SubagentCoordinator.TaskStatus.COMPLETED);
        assertThat(entry.result()).isEqualTo("delayed-result");
    }

    @Test
    void maxConcurrentEnforced() {
        Agent slowAgent = delayedAgent("slow", 5000);
        coordinator.submit("s1", "t1", slowAgent, "p1");
        coordinator.submit("s1", "t2", slowAgent, "p2");
        coordinator.submit("s1", "t3", slowAgent, "p3");

        String fourth = coordinator.submit("s1", "t4", slowAgent, "p4");
        assertThat(fourth).isNull();
    }

    @Test
    void differentSessionsAreIndependent() {
        Agent slowAgent = delayedAgent("slow", 5000);
        coordinator.submit("s1", "t1", slowAgent, "p1");
        coordinator.submit("s1", "t2", slowAgent, "p2");
        coordinator.submit("s1", "t3", slowAgent, "p3");

        String fromOtherSession = coordinator.submit("s2", "t1", slowAgent, "p1");
        assertThat(fromOtherSession).isNotNull();
    }

    @Test
    void getSessionTasksFiltersCorrectly() {
        Agent stub = stubAgent("r");
        coordinator.submit("s1", "task-a", stub, "p");
        coordinator.submit("s2", "task-b", stub, "p");

        assertThat(coordinator.getSessionTasks("s1")).hasSize(1);
        assertThat(coordinator.getSessionTasks("s1").get(0).description()).isEqualTo("task-a");
    }

    @Test
    void cleanupRemovesSessionData() {
        Agent stub = stubAgent("r");
        coordinator.submit("s1", "task", stub, "p");
        assertThat(coordinator.getSessionTasks("s1")).hasSize(1);

        coordinator.cleanup("s1");
        assertThat(coordinator.getSessionTasks("s1")).isEmpty();
        assertThat(coordinator.activeCount("s1")).isEqualTo(0);
    }

    @Test
    void failedTaskRecordsError() throws InterruptedException {
        Agent failAgent = new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.error(new RuntimeException("oops"));
            }
            @Override public String id() { return "fail"; }
            @Override public String name() { return "fail"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };

        String taskId = coordinator.submit("s1", "will-fail", failAgent, "p");
        Thread.sleep(100);

        SubagentCoordinator.TaskEntry entry = coordinator.getTask(taskId);
        assertThat(entry.status()).isEqualTo(SubagentCoordinator.TaskStatus.FAILED);
        assertThat(entry.result()).contains("oops");
    }

    private Agent stubAgent(String response) {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, response));
            }
            @Override public String id() { return "stub"; }
            @Override public String name() { return "stub"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
    }

    private Agent delayedAgent(String response, long delayMs) {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.delay(Duration.ofMillis(delayMs))
                        .map(ignored -> Msg.of(MsgRole.ASSISTANT, response));
            }
            @Override public String id() { return "delayed"; }
            @Override public String name() { return "delayed"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
    }
}
