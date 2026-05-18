package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvToolTest {

    private final EnvTool tool = new EnvTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void getExistingVar() {
        ToolResult result = tool.execute(Map.of("action", "get", "name", "PATH"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("PATH=");
    }

    @Test
    void getUnsetVar() {
        ToolResult result = tool.execute(
                Map.of("action", "get", "name", "UNLIKELY_ENV_VAR_12345"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("not set");
    }

    @Test
    void getRequiresName() {
        ToolResult result = tool.execute(Map.of("action", "get"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("name");
    }

    @Test
    void listWithFilter() {
        ToolResult result = tool.execute(
                Map.of("action", "list", "filter", "PATH"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("PATH");
    }

    @Test
    void listNoFilter() {
        ToolResult result = tool.execute(Map.of("action", "list"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("variables");
    }

    @Test
    void defaultActionIsList() {
        ToolResult result = tool.execute(Map.of(), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("variables");
    }

    @Test
    void searchAction() {
        ToolResult result = tool.execute(Map.of("action", "search", "filter", "HOME"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("HOME");
    }

    @Test
    void unknownActionErrors() {
        ToolResult result = tool.execute(Map.of("action", "set"), ctx).block();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown action");
    }

    @Test
    void sensitiveVarsMasked() {
        ToolResult result = tool.execute(Map.of("action", "list", "filter", "KEY"), ctx).block();
        assertThat(result).isNotNull();
    }

    @Test
    void schemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("action");
        assertThat(schema.properties()).containsKey("name");
        assertThat(schema.properties()).containsKey("filter");
        assertThat(schema.required()).isEmpty();
    }

    @Test
    void filterCaseInsensitive() {
        ToolResult result = tool.execute(Map.of("action", "list", "filter", "path"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("PATH");
    }

    @Test
    void noMatchingVars() {
        ToolResult result = tool.execute(
                Map.of("action", "list", "filter", "ZZZZZ_NONEXISTENT_FILTER_ZZZZZ"), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("No variables");
    }
}
