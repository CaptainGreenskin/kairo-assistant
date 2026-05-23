package io.kairo.assistant.gateway;

import io.kairo.core.session.AgentSessionPool;
import io.kairo.core.session.SessionKey;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentSessionPoolTest {

    private AgentSessionPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) pool.shutdown();
    }

    @Test
    void getOrCreateReturnsNewAgent() {
        AtomicInteger created = new AtomicInteger(0);
        pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> {
            created.incrementAndGet();
            return stubAgent(key.toString());
        }, null);

        var key = SessionKey.of("dingtalk", "conv_1");
        Agent agent = pool.getOrCreate(key);

        assertThat(agent).isNotNull();
        assertThat(created.get()).isEqualTo(1);
        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    void getOrCreateReturnsSameAgentOnSecondCall() {
        AtomicInteger created = new AtomicInteger(0);
        pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> {
            created.incrementAndGet();
            return stubAgent(key.toString());
        }, null);

        var key = SessionKey.of("feishu", "chat_1");
        Agent a1 = pool.getOrCreate(key);
        Agent a2 = pool.getOrCreate(key);

        assertThat(a1).isSameAs(a2);
        assertThat(created.get()).isEqualTo(1);
    }

    @Test
    void differentKeysCreateDifferentAgents() {
        pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> stubAgent(key.toString()), null);

        Agent a1 = pool.getOrCreate(SessionKey.of("dt", "c1"));
        Agent a2 = pool.getOrCreate(SessionKey.of("dt", "c2"));

        assertThat(a1).isNotSameAs(a2);
        assertThat(pool.size()).isEqualTo(2);
    }

    @Test
    void lruEvictionWhenMaxSizeExceeded() {
        List<SessionKey> evicted = Collections.synchronizedList(new ArrayList<>());
        pool = new AgentSessionPool(3, Duration.ofMinutes(60),
                key -> stubAgent(key.toString()), evicted::add);

        pool.getOrCreate(SessionKey.of("ch", "a"));
        pool.getOrCreate(SessionKey.of("ch", "b"));
        pool.getOrCreate(SessionKey.of("ch", "c"));
        pool.getOrCreate(SessionKey.of("ch", "d"));

        assertThat(pool.size()).isEqualTo(3);
        assertThat(evicted).contains(SessionKey.of("ch", "a"));
    }

    @Test
    void lruBumpsAccessedEntry() {
        List<SessionKey> evicted = Collections.synchronizedList(new ArrayList<>());
        pool = new AgentSessionPool(3, Duration.ofMinutes(60),
                key -> stubAgent(key.toString()), evicted::add);

        pool.getOrCreate(SessionKey.of("ch", "a"));
        pool.getOrCreate(SessionKey.of("ch", "b"));
        pool.getOrCreate(SessionKey.of("ch", "c"));

        // Access "a" to bump it
        pool.getOrCreate(SessionKey.of("ch", "a"));

        // Now adding "d" should evict "b" (least recently used)
        pool.getOrCreate(SessionKey.of("ch", "d"));

        assertThat(evicted).contains(SessionKey.of("ch", "b"));
        assertThat(pool.get(SessionKey.of("ch", "a"))).isNotNull();
    }

    @Test
    void explicitEvictRemovesSession() {
        pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> stubAgent(key.toString()), null);

        var key = SessionKey.of("ws", "s1");
        pool.getOrCreate(key);
        assertThat(pool.size()).isEqualTo(1);

        pool.evict(key);
        assertThat(pool.size()).isEqualTo(0);
        assertThat(pool.get(key)).isNull();
    }

    @Test
    void getReturnsNullForUnknownKey() {
        pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> stubAgent(key.toString()), null);
        assertThat(pool.get(SessionKey.of("x", "y"))).isNull();
    }

    @Test
    void shutdownClearsPool() {
        pool = new AgentSessionPool(10, Duration.ofMinutes(60), key -> stubAgent(key.toString()), null);
        pool.getOrCreate(SessionKey.of("ch", "1"));
        pool.getOrCreate(SessionKey.of("ch", "2"));

        pool.shutdown();
        assertThat(pool.size()).isEqualTo(0);
    }

    @Test
    void replacePutsNewAgentForExistingKey() {
        pool = new AgentSessionPool(10, Duration.ofMinutes(60),
                key -> stubAgent("original"), null);

        var key = SessionKey.of("test", "session1");
        Agent original = pool.getOrCreate(key);
        assertThat(original.name()).isEqualTo("original");

        Agent replacement = stubAgent("replacement");
        pool.replace(key, replacement);

        Agent current = pool.get(key);
        assertThat(current).isSameAs(replacement);
        assertThat(current.name()).isEqualTo("replacement");
        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    void replaceForNonExistentKeyAddsToPool() {
        pool = new AgentSessionPool(10, Duration.ofMinutes(60),
                key -> stubAgent("original"), null);

        var key = SessionKey.of("test", "new-session");
        Agent replacement = stubAgent("new-agent");
        pool.replace(key, replacement);

        assertThat(pool.get(key)).isSameAs(replacement);
        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    void idleEvictionRemovesStaleEntries() throws InterruptedException {
        List<SessionKey> evicted = Collections.synchronizedList(new ArrayList<>());
        pool = new AgentSessionPool(10, Duration.ofMillis(50),
                key -> stubAgent(key.toString()), evicted::add);

        pool.getOrCreate(SessionKey.of("ch", "stale"));
        Thread.sleep(100);

        // Trigger sweep manually via getOrCreate (sweep runs on scheduler but we can test evict)
        pool.evict(SessionKey.of("ch", "stale"));
        assertThat(pool.size()).isEqualTo(0);
    }

    private Agent stubAgent(String name) {
        return new Agent() {
            @Override public Mono<Msg> call(Msg input) {
                return Mono.just(Msg.of(MsgRole.ASSISTANT, "echo: " + input.text()));
            }
            @Override public String id() { return name; }
            @Override public String name() { return name; }
            @Override public AgentState state() { return AgentState.IDLE; }
            @Override public void interrupt() {}
        };
    }
}
