package io.kairo.assistant.gateway;

import io.kairo.core.session.SessionKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SessionKeyTest {

    @Test
    void createsFromChannelAndDestination() {
        var key = SessionKey.of("dingtalk", "conv_123");
        assertThat(key.channelId()).isEqualTo("dingtalk");
        assertThat(key.destination()).isEqualTo("conv_123");
    }

    @Test
    void toStringFormatIsChannelColonDestination() {
        var key = SessionKey.of("feishu", "chat_456");
        assertThat(key.toString()).isEqualTo("feishu:chat_456");
    }

    @Test
    void equalityByValue() {
        var k1 = SessionKey.of("ws", "abc");
        var k2 = SessionKey.of("ws", "abc");
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    }

    @Test
    void differentDestinationsAreNotEqual() {
        var k1 = SessionKey.of("dingtalk", "conv_1");
        var k2 = SessionKey.of("dingtalk", "conv_2");
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void nullChannelIdThrows() {
        assertThatThrownBy(() -> SessionKey.of(null, "dest"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullDestinationThrows() {
        assertThatThrownBy(() -> SessionKey.of("ch", null))
                .isInstanceOf(NullPointerException.class);
    }
}
