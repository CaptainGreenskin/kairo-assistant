package io.kairo.assistant.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CredentialPoolTest {

    @Test
    void roundRobinRotation() {
        CredentialPool pool = new CredentialPool(List.of("key1", "key2", "key3"));

        String first = pool.next();
        String second = pool.next();
        String third = pool.next();
        String fourth = pool.next();

        assertThat(List.of(first, second, third)).containsExactlyInAnyOrder("key1", "key2", "key3");
        assertThat(fourth).isEqualTo(first);
    }

    @Test
    void skipsRateLimitedKey() {
        CredentialPool pool = new CredentialPool(List.of("key1", "key2"));
        pool.markRateLimited("key1", 60);

        assertThat(pool.next()).isEqualTo("key2");
        assertThat(pool.next()).isEqualTo("key2");
    }

    @Test
    void fallsBackWhenAllLimited() {
        CredentialPool pool = new CredentialPool(List.of("key1", "key2"));
        pool.markRateLimited("key1", 60);
        pool.markRateLimited("key2", 120);

        String key = pool.next();
        assertThat(key).isIn("key1", "key2");
    }

    @Test
    void emptyPoolThrows() {
        CredentialPool pool = new CredentialPool(List.of());
        assertThatThrownBy(pool::next).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sizeAndAvailable() {
        CredentialPool pool = new CredentialPool(List.of("a", "b", "c"));
        assertThat(pool.size()).isEqualTo(3);
        assertThat(pool.availableCount()).isEqualTo(3);

        pool.markRateLimited("a", 60);
        assertThat(pool.availableCount()).isEqualTo(2);
    }

    @Test
    void markFailedDisablesAfterThreshold() {
        CredentialPool pool = new CredentialPool(List.of("key1", "key2"));
        pool.markFailed("key1");
        pool.markFailed("key1");
        assertThat(pool.availableCount()).isEqualTo(2);

        pool.markFailed("key1");
        assertThat(pool.availableCount()).isEqualTo(1);
    }
}
