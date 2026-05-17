package io.kairo.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.context.ContextSource;
import io.kairo.api.memory.MemoryStore;
import io.kairo.core.memory.InMemoryMemoryStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantContextSourcesTest {

    @Test
    void defaultsReturnThreeSources() {
        MemoryStore store = new InMemoryMemoryStore();
        List<ContextSource> sources = AssistantContextSources.defaults(store);
        assertThat(sources).hasSize(3);
    }

    @Test
    void dateTimeSourceContainsDate() {
        ContextSource source = AssistantContextSources.dateTimeSource();
        assertThat(source.getName()).isEqualTo("datetime");
        assertThat(source.isActive()).isTrue();
        assertThat(source.collect()).contains("Current date/time:");
    }

    @Test
    void systemInfoSourceContainsOS() {
        ContextSource source = AssistantContextSources.systemInfoSource();
        assertThat(source.getName()).isEqualTo("system-info");
        assertThat(source.collect()).contains("System:");
    }
}
