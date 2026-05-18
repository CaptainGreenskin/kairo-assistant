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

    @Test
    void dateTimeSourceContainsTimezone() {
        ContextSource source = AssistantContextSources.dateTimeSource();
        assertThat(source.collect()).contains("timezone:");
    }

    @Test
    void systemInfoSourceContainsUser() {
        ContextSource source = AssistantContextSources.systemInfoSource();
        assertThat(source.collect()).contains("user:");
    }

    @Test
    void recentMemorySourceReturnsEmptyWhenNoMemories() {
        MemoryStore store = new InMemoryMemoryStore();
        ContextSource source = AssistantContextSources.recentMemorySource(store);
        assertThat(source.getName()).isEqualTo("recent-memories");
        assertThat(source.collect()).isEmpty();
    }

    @Test
    void defaultSourceNamesAreDistinct() {
        MemoryStore store = new InMemoryMemoryStore();
        List<ContextSource> sources = AssistantContextSources.defaults(store);
        List<String> names = sources.stream().map(ContextSource::getName).toList();
        assertThat(names).containsExactly("datetime", "system-info", "recent-memories");
    }

    @Test
    void allSourcesAreActive() {
        MemoryStore store = new InMemoryMemoryStore();
        List<ContextSource> sources = AssistantContextSources.defaults(store);
        for (ContextSource s : sources) {
            assertThat(s.isActive()).as(s.getName()).isTrue();
        }
    }
}
