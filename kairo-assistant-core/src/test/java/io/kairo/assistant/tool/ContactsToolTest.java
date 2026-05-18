package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.core.memory.InMemoryStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContactsToolTest {

    private final InMemoryStore store = new InMemoryStore();
    private final ContactsTool tool = new ContactsTool();
    private final ToolContext ctx = new ToolContext("a", "s", Map.of("memoryStore", store));

    @Test
    void addAndList() {
        ToolResult add = tool.execute(
                Map.of("action", "add", "name", "Alice", "email", "alice@example.com"), ctx).block();
        assertThat(add.isError()).isFalse();
        assertThat(add.content()).contains("Alice");

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("Alice").contains("alice@example.com");
    }

    @Test
    void addWithPhoneAndNotes() {
        ToolResult add = tool.execute(
                Map.of("action", "add", "name", "Dave", "phone", "555-1234", "notes", "VIP"), ctx).block();
        assertThat(add.isError()).isFalse();

        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(list.content()).contains("Dave").contains("555-1234").contains("VIP");
    }

    @Test
    void searchContacts() {
        tool.execute(Map.of("action", "add", "name", "Bob"), ctx).block();
        ToolResult r = tool.execute(Map.of("action", "search", "query", "bob"), ctx).block();
        assertThat(r.content()).contains("Bob");
    }

    @Test
    void searchNoResults() {
        ToolResult r = tool.execute(Map.of("action", "search", "query", "zzz_nonexistent"), ctx).block();
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).contains("No contacts");
    }

    @Test
    void searchRequiresQuery() {
        ToolResult r = tool.execute(Map.of("action", "search"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("query");
    }

    @Test
    void addRequiresName() {
        ToolResult r = tool.execute(Map.of("action", "add"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void addBlankNameErrors() {
        ToolResult r = tool.execute(Map.of("action", "add", "name", "  "), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void noStoreErrors() {
        ToolContext noStore = new ToolContext("a", "s", null);
        ToolResult r = tool.execute(Map.of("action", "list"), noStore).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("MemoryStore");
    }

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of("name", "Test"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("action");
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "export"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("Unknown action");
    }

    @Test
    void updateContactFields() {
        tool.execute(Map.of("action", "add", "name", "Charlie", "email", "old@mail.com"), ctx).block();
        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        String id = extractId(list.content());

        ToolResult update = tool.execute(
                Map.of("action", "update", "id", id, "email", "new@mail.com"), ctx).block();
        assertThat(update.isError()).isFalse();
        assertThat(update.content()).contains("Updated");

        ToolResult after = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(after.content()).contains("new@mail.com");
    }

    @Test
    void updateNonexistentIdErrors() {
        ToolResult r = tool.execute(
                Map.of("action", "update", "id", "contact:zzz", "name", "Nobody"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("not found");
    }

    @Test
    void updateRequiresId() {
        ToolResult r = tool.execute(Map.of("action", "update", "name", "X"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("id");
    }

    @Test
    void deleteContact() {
        tool.execute(Map.of("action", "add", "name", "Deletable"), ctx).block();
        ToolResult list = tool.execute(Map.of("action", "list"), ctx).block();
        String id = extractId(list.content());

        ToolResult del = tool.execute(Map.of("action", "delete", "id", id), ctx).block();
        assertThat(del.isError()).isFalse();
        assertThat(del.content()).contains("Deleted");
    }

    @Test
    void deleteRequiresId() {
        ToolResult r = tool.execute(Map.of("action", "delete"), ctx).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("id");
    }

    @Test
    void schemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("name");
        assertThat(schema.properties()).containsKey("email");
        assertThat(schema.properties()).containsKey("phone");
        assertThat(schema.properties()).containsKey("query");
        assertThat(schema.properties()).containsKey("id");
        assertThat(schema.required()).containsExactly("action");
    }

    @Test
    void emptyListMessage() {
        InMemoryStore empty = new InMemoryStore();
        ToolContext emptyCtx = new ToolContext("a", "s", Map.of("memoryStore", empty));
        ToolResult r = tool.execute(Map.of("action", "list"), emptyCtx).block();
        assertThat(r.content()).contains("No contacts");
    }

    private String extractId(String content) {
        int start = content.indexOf('[') + 1;
        int end = content.indexOf(']');
        return content.substring(start, end);
    }
}
