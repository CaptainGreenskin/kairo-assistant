package io.kairo.assistant.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class UnifiedGatewayTest {

    private UnifiedGateway gateway;

    @AfterEach
    void tearDown() {
        if (gateway != null) gateway.shutdown();
    }

    @Test
    void routeCallsAgentAndReturnsResponse() {
        var pool = new AgentSessionPool(10, Duration.ofMinutes(60),
                key -> echoAgent(), null);
        gateway = new UnifiedGateway(pool);

        var key = SessionKey.of("dingtalk", "conv_1");
        Msg response = gateway.route(key, Msg.of(MsgRole.USER, "hello")).block();

        assertThat(response).isNotNull();
        assertThat(response.text()).isEqualTo("echo: hello");
    }

    @Test
    void routeIsolatesSessions() {
        AtomicInteger callCount = new AtomicInteger(0);
        var pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> {
            callCount.incrementAndGet();
            return echoAgent();
        }, null);
        gateway = new UnifiedGateway(pool);

        var k1 = SessionKey.of("dt", "c1");
        var k2 = SessionKey.of("dt", "c2");

        gateway.route(k1, Msg.of(MsgRole.USER, "a")).block();
        gateway.route(k2, Msg.of(MsgRole.USER, "b")).block();

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void interruptCallsAgentInterrupt() {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        var pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "ok"));
            }
            @Override public String id() { return "test"; }
            @Override public String name() { return "test"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() { interrupted.set(true); }
        }, null);
        gateway = new UnifiedGateway(pool);

        var key = SessionKey.of("ws", "s1");
        gateway.route(key, Msg.of(MsgRole.USER, "init")).block();
        gateway.interrupt(key);

        assertThat(interrupted.get()).isTrue();
    }

    @Test
    void interruptUnknownSessionIsNoOp() {
        var pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> echoAgent(), null);
        gateway = new UnifiedGateway(pool);

        gateway.interrupt(SessionKey.of("unknown", "key"));
        // no exception
    }

    @Test
    void poolAccessibleViaGateway() {
        var pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> echoAgent(), null);
        gateway = new UnifiedGateway(pool);

        assertThat(gateway.pool()).isSameAs(pool);
    }

    private Agent echoAgent() {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "echo: " + input.text()));
            }
            @Override public String id() { return "echo"; }
            @Override public String name() { return "Echo"; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
    }
}
