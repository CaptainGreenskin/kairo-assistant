package io.kairo.assistant.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.assistant.gateway.SessionKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionMirrorTest {

    @Test
    void subscriberReceivesMessages() {
        SessionMirror mirror = new SessionMirror();
        SessionKey key = SessionKey.of("dingtalk", "user1");
        List<String> received = Collections.synchronizedList(new ArrayList<>());

        mirror.subscribe(key, received::add);
        mirror.onInbound(key, "dingtalk", "hello");

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("hello");
        assertThat(received.get(0)).contains("inbound");
    }

    @Test
    void userSubscriberReceivesAll() {
        SessionMirror mirror = new SessionMirror();
        SessionKey key = SessionKey.of("feishu", "user1");
        List<String> received = Collections.synchronizedList(new ArrayList<>());

        mirror.subscribeUser("user1", received::add);
        mirror.onOutbound(key, "feishu", "response");

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("outbound");
    }

    @Test
    void unsubscribeStopsMessages() {
        SessionMirror mirror = new SessionMirror();
        SessionKey key = SessionKey.of("slack", "u2");
        List<String> received = new ArrayList<>();

        java.util.function.Consumer<String> listener = received::add;
        mirror.subscribe(key, listener);
        mirror.onInbound(key, "slack", "msg1");

        mirror.unsubscribe(key, listener);
        mirror.onInbound(key, "slack", "msg2");

        assertThat(received).hasSize(1);
    }

    @Test
    void statsReportsCorrectCounts() {
        SessionMirror mirror = new SessionMirror();
        assertThat(mirror.subscriberCount()).isEqualTo(0);

        mirror.subscribe(SessionKey.of("a", "1"), s -> {});
        mirror.subscribeUser("u1", s -> {});

        assertThat(mirror.subscriberCount()).isEqualTo(2);
    }

    @Test
    void noSubscriberDoesNotThrow() {
        SessionMirror mirror = new SessionMirror();
        SessionKey key = SessionKey.of("x", "y");
        mirror.onInbound(key, "x", "no one listening");
    }

    @Test
    void globalSubscriberReceivesAll() {
        SessionMirror mirror = new SessionMirror();
        List<String> received = Collections.synchronizedList(new ArrayList<>());

        mirror.subscribeAll(received::add);
        mirror.onInbound(SessionKey.of("a", "1"), "a", "msg1");
        mirror.onOutbound(SessionKey.of("b", "2"), "b", "msg2");

        assertThat(received).hasSize(2);
        assertThat(received.get(0)).contains("msg1");
        assertThat(received.get(1)).contains("msg2");
    }

    @Test
    void unsubscribeAllStopsMessages() {
        SessionMirror mirror = new SessionMirror();
        List<String> received = new ArrayList<>();
        java.util.function.Consumer<String> listener = received::add;

        mirror.subscribeAll(listener);
        mirror.onInbound(SessionKey.of("x", "y"), "x", "first");

        mirror.unsubscribeAll(listener);
        mirror.onInbound(SessionKey.of("x", "y"), "x", "second");

        assertThat(received).hasSize(1);
    }
}
