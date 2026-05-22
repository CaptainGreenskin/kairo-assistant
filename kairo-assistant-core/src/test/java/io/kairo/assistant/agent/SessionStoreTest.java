/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {

    @Test
    void loadEmptyWhenFileMissing(@TempDir Path tmp) {
        SessionStore store = new SessionStore(tmp);
        assertThat(store.load("nonexistent")).isEmpty();
    }

    @Test
    void appendThenLoadRoundtripsTextContent(@TempDir Path tmp) {
        SessionStore store = new SessionStore(tmp);
        List<Msg> msgs =
                List.of(Msg.of(MsgRole.USER, "hi"), Msg.of(MsgRole.ASSISTANT, "hello there"));
        store.append("sess-1", msgs);

        List<Msg> loaded = store.load("sess-1");
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).role()).isEqualTo(MsgRole.USER);
        assertThat(((Content.TextContent) loaded.get(0).contents().get(0)).text()).isEqualTo("hi");
        assertThat(loaded.get(1).role()).isEqualTo(MsgRole.ASSISTANT);
    }

    @Test
    void appendIsAppendingNotReplacing(@TempDir Path tmp) {
        SessionStore store = new SessionStore(tmp);
        store.append("sess-2", List.of(Msg.of(MsgRole.USER, "turn 1")));
        store.append("sess-2", List.of(Msg.of(MsgRole.USER, "turn 2")));
        assertThat(store.load("sess-2")).hasSize(2);
    }

    @Test
    void preservesToolUseAndToolResultContent(@TempDir Path tmp) {
        SessionStore store = new SessionStore(tmp);
        Msg toolUse =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .addContent(
                                new Content.ToolUseContent(
                                        "use-1", "search", Map.of("q", "weather")))
                        .build();
        Msg toolResult =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .addContent(new Content.ToolResultContent("use-1", "Sunny, 21C", false))
                        .build();
        store.append("sess-3", List.of(toolUse, toolResult));

        List<Msg> loaded = store.load("sess-3");
        assertThat(loaded).hasSize(2);
        var tu = (Content.ToolUseContent) loaded.get(0).contents().get(0);
        assertThat(tu.toolName()).isEqualTo("search");
        assertThat(tu.input()).containsEntry("q", "weather");
        var tr = (Content.ToolResultContent) loaded.get(1).contents().get(0);
        assertThat(tr.toolUseId()).isEqualTo("use-1");
        assertThat(tr.content()).isEqualTo("Sunny, 21C");
        assertThat(tr.isError()).isFalse();
    }

    @Test
    void sessionIdsAreSanitizedForFilesystem(@TempDir Path tmp) {
        SessionStore store = new SessionStore(tmp);
        // Slashes / spaces shouldn't escape the sessions dir
        Path file = store.pathFor("../escape attempt/with weird chars!");
        assertThat(file.startsWith(store.root())).isTrue();
        assertThat(file.getFileName().toString()).doesNotContain("/", "\\", " ");
    }

    @Test
    void blankSessionIdThrows(@TempDir Path tmp) {
        SessionStore store = new SessionStore(tmp);
        assertThatThrownBy(() -> store.pathFor("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.pathFor(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appendSkipsWhenNoMessages(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.append("sess-empty", List.of());
        // No file should have been created
        assertThat(Files.exists(store.pathFor("sess-empty"))).isFalse();
    }

    @Test
    void corruptedLinesAreSkipped(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.append("sess-good", List.of(Msg.of(MsgRole.USER, "first")));
        // Inject a garbage line in the middle
        Path file = store.pathFor("sess-good");
        String existing = Files.readString(file);
        Files.writeString(
                file, existing + "{not valid json\n" + existing);
        List<Msg> loaded = store.load("sess-good");
        // Two valid lines remain (the duplicate + the original); the garbage line is dropped
        assertThat(loaded).hasSize(2);
    }
}
