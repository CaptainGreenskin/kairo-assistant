package io.kairo.assistant.gateway;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class UnifiedGateway {

    private static final Logger log = LoggerFactory.getLogger(UnifiedGateway.class);

    private final AgentSessionPool pool;
    private final ConcurrentHashMap<SessionKey, Semaphore> callLocks = new ConcurrentHashMap<>();

    public UnifiedGateway(AgentSessionPool pool) {
        this.pool = pool;
    }

    public Mono<Msg> route(SessionKey key, Msg input) {
        Agent agent = pool.getOrCreate(key);
        Semaphore sem = callLocks.computeIfAbsent(key, k -> new Semaphore(1));

        return Mono.defer(() -> {
            if (!sem.tryAcquire()) {
                log.debug("Session [{}] busy, interrupting previous call", key);
                agent.interrupt();
                try {
                    sem.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Mono.error(e);
                }
            }
            return agent.call(input)
                    .doFinally(signal -> sem.release());
        });
    }

    public void interrupt(SessionKey key) {
        Agent agent = pool.get(key);
        if (agent != null) {
            agent.interrupt();
            log.info("Interrupted session [{}]", key);
        }
    }

    public AgentSessionPool pool() {
        return pool;
    }

    public void shutdown() {
        pool.shutdown();
        callLocks.clear();
    }
}
