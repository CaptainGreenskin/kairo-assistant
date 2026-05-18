package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingDeltaRouterTest {

    @Test
    void compositeConsumerFansOutToAllSubscribers() {
        var router = new StreamingDeltaRouter();
        List<String> received1 = Collections.synchronizedList(new ArrayList<>());
        List<String> received2 = Collections.synchronizedList(new ArrayList<>());

        router.subscribe("s1", received1::add);
        router.subscribe("s2", received2::add);

        router.compositeConsumer().accept("hello");

        assertEquals(List.of("hello"), received1);
        assertEquals(List.of("hello"), received2);
    }

    @Test
    void unsubscribeStopsDelivery() {
        var router = new StreamingDeltaRouter();
        List<String> received = new ArrayList<>();

        router.subscribe("sub", received::add);
        router.compositeConsumer().accept("first");
        router.unsubscribe("sub");
        router.compositeConsumer().accept("second");

        assertEquals(List.of("first"), received);
    }

    @Test
    void subscriberExceptionDoesNotAffectOthers() {
        var router = new StreamingDeltaRouter();
        List<String> received = new ArrayList<>();

        router.subscribe("bad", delta -> { throw new RuntimeException("boom"); });
        router.subscribe("good", received::add);

        assertDoesNotThrow(() -> router.compositeConsumer().accept("test"));
        assertEquals(List.of("test"), received);
    }

    @Test
    void subscriberCountReflectsActiveSubscriptions() {
        var router = new StreamingDeltaRouter();
        assertEquals(0, router.subscriberCount());

        router.subscribe("a", delta -> {});
        assertEquals(1, router.subscriberCount());

        router.subscribe("b", delta -> {});
        assertEquals(2, router.subscriberCount());

        router.unsubscribe("a");
        assertEquals(1, router.subscriberCount());
    }

    @Test
    void subscribeSameIdReplacesCallback() {
        var router = new StreamingDeltaRouter();
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();

        router.subscribe("dup", first::add);
        router.subscribe("dup", second::add);

        router.compositeConsumer().accept("msg");
        assertTrue(first.isEmpty());
        assertEquals(List.of("msg"), second);
        assertEquals(1, router.subscriberCount());
    }

    @Test
    void unsubscribeNonExistentIdIsNoOp() {
        var router = new StreamingDeltaRouter();
        assertDoesNotThrow(() -> router.unsubscribe("nonexistent"));
    }

    @Test
    void noSubscribersDoesNotFail() {
        var router = new StreamingDeltaRouter();
        assertDoesNotThrow(() -> router.compositeConsumer().accept("ignored"));
    }

    @Test
    void multipleDeltas() {
        var router = new StreamingDeltaRouter();
        List<String> received = new ArrayList<>();
        router.subscribe("sub", received::add);

        router.compositeConsumer().accept("a");
        router.compositeConsumer().accept("b");
        router.compositeConsumer().accept("c");

        assertEquals(List.of("a", "b", "c"), received);
    }
}
