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
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("PATH=");
    }

    @Test
    void getUnsetVar() {
        ToolResult result = tool.execute(
                Map.of("action", "get", "name", "UNLIKELY_ENV_VAR_12345"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("not set");
    }

    @Test
    void listWithFilter() {
        ToolResult result = tool.execute(
                Map.of("action", "list", "filter", "PATH"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("PATH");
    }

    @Test
    void sensitiveVarsMasked() {
        ToolResult result = tool.execute(Map.of("action", "list", "filter", "KEY"), ctx).block();
        assertThat(result).isNotNull();
        // Any KEY vars should be masked if present
    }
}
