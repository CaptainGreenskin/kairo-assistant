package io.kairo.assistant.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.assistant.gateway.SessionKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionAwareDeltaRouterTest {

    private SessionAwareDeltaRouter router;

    @BeforeEach
    void setUp() {
        router = new SessionAwareDeltaRouter();
    }

    @Test
    void deltaRoutedToSubscribedSession() {
        var key = SessionKey.of("dingtalk", "conv_1");
        List<String> received = new ArrayList<>();

        router.subscribe(key, "sub-1", received::add);
        router.consumerFor(key).accept("hello");

        assertThat(received).containsExactly("hello");
    }

    @Test
    void deltaIsolatedBetweenSessions() {
        var keyA = SessionKey.of("dt", "a");
        var keyB = SessionKey.of("dt", "b");
        List<String> receivedA = Collections.synchronizedList(new ArrayList<>());
        List<String> receivedB = Collections.synchronizedList(new ArrayList<>());

        router.subscribe(keyA, "sub-a", receivedA::add);
        router.subscribe(keyB, "sub-b", receivedB::add);

        router.consumerFor(keyA).accept("for-a");
        router.consumerFor(keyB).accept("for-b");

        assertThat(receivedA).containsExactly("for-a");
        assertThat(receivedB).containsExactly("for-b");
    }

    @Test
    void multipleSubscribersWithinSameSession() {
        var key = SessionKey.of("ws", "s1");
        List<String> sub1 = new ArrayList<>();
        List<String> sub2 = new ArrayList<>();

        router.subscribe(key, "ws-1", sub1::add);
        router.subscribe(key, "ws-2", sub2::add);
        router.consumerFor(key).accept("delta");

        assertThat(sub1).containsExactly("delta");
        assertThat(sub2).containsExactly("delta");
    }

    @Test
    void unsubscribeStopsDelivery() {
        var key = SessionKey.of("feishu", "chat_1");
        List<String> received = new ArrayList<>();

        router.subscribe(key, "sub-1", received::add);
        router.unsubscribe(key, "sub-1");
        router.consumerFor(key).accept("should-not-arrive");

        assertThat(received).isEmpty();
    }

    @Test
    void removeSessionClearsAllSubscribers() {
        var key = SessionKey.of("dt", "c1");
        List<String> received = new ArrayList<>();

        router.subscribe(key, "sub-1", received::add);
        router.subscribe(key, "sub-2", received::add);
        router.removeSession(key);
        router.consumerFor(key).accept("nope");

        assertThat(received).isEmpty();
        assertThat(router.sessionCount()).isEqualTo(0);
    }

    @Test
    void consumerForUnknownSessionIsNoOp() {
        var key = SessionKey.of("unknown", "x");
        Consumer<String> consumer = router.consumerFor(key);
        consumer.accept("nothing happens");
        // no exception
    }

    @Test
    void subscriberExceptionDoesNotAffectOthers() {
        var key = SessionKey.of("ws", "s1");
        List<String> healthy = new ArrayList<>();

        router.subscribe(key, "bad", delta -> { throw new RuntimeException("boom"); });
        router.subscribe(key, "good", healthy::add);
        router.consumerFor(key).accept("test");

        assertThat(healthy).containsExactly("test");
    }

    @Test
    void sessionCountTracksActiveSessions() {
        router.subscribe(SessionKey.of("a", "1"), "s", delta -> {});
        router.subscribe(SessionKey.of("b", "2"), "s", delta -> {});

        assertThat(router.sessionCount()).isEqualTo(2);

        router.removeSession(SessionKey.of("a", "1"));
        assertThat(router.sessionCount()).isEqualTo(1);
    }
}
