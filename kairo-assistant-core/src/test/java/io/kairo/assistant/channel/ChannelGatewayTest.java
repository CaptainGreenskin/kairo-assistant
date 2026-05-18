package io.kairo.assistant.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ChannelGatewayTest {

    @Test
    void truncateShortString() throws Exception {
        Method truncate = ChannelGateway.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);
        assertThat(truncate.invoke(null, "hello", 100)).isEqualTo("hello");
    }

    @Test
    void truncateLongString() throws Exception {
        Method truncate = ChannelGateway.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);

        String input = "a".repeat(150);
        String result = (String) truncate.invoke(null, input, 100);
        assertThat(result).hasSize(103);
        assertThat(result).endsWith("...");
    }

    @Test
    void truncateExactLength() throws Exception {
        Method truncate = ChannelGateway.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);

        String input = "a".repeat(100);
        String result = (String) truncate.invoke(null, input, 100);
        assertThat(result).hasSize(100);
        assertThat(result).doesNotEndWith("...");
    }

    @Test
    void constructorAcceptsNullChannel() {
        assertThat(new ChannelGateway(null, null)).isNotNull();
    }

    @Test
    void truncateEmpty() throws Exception {
        Method truncate = ChannelGateway.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);
        assertThat(truncate.invoke(null, "", 100)).isEqualTo("");
    }

    @Test
    void truncateOneBeyondLimit() throws Exception {
        Method truncate = ChannelGateway.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);

        String input = "a".repeat(101);
        String result = (String) truncate.invoke(null, input, 100);
        assertThat(result).endsWith("...");
    }

    @Test
    void truncatePreservesPrefix() throws Exception {
        Method truncate = ChannelGateway.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);

        String input = "ABC" + "x".repeat(200);
        String result = (String) truncate.invoke(null, input, 50);
        assertThat(result).startsWith("ABC");
    }
}
