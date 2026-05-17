package io.kairo.assistant.channel;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelGatewayTest {

    @Test
    void truncateShortString() throws Exception {
        Method truncate = ChannelGateway.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);

        assertEquals("hello", truncate.invoke(null, "hello", 100));
    }

    @Test
    void truncateLongString() throws Exception {
        Method truncate = ChannelGateway.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);

        String input = "a".repeat(150);
        String result = (String) truncate.invoke(null, input, 100);
        assertEquals(103, result.length());
        assertTrue(result.endsWith("..."));
    }

    @Test
    void truncateExactLength() throws Exception {
        Method truncate = ChannelGateway.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);

        String input = "a".repeat(100);
        String result = (String) truncate.invoke(null, input, 100);
        assertEquals(100, result.length());
        assertFalse(result.endsWith("..."));
    }

    @Test
    void constructorAcceptsNullChannel() {
        assertDoesNotThrow(() -> new ChannelGateway(null, null));
    }
}
