package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemInfoToolTest {

    private final SystemInfoTool tool = new SystemInfoTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void allSections() {
        ToolResult result = tool.execute(Map.of("section", "all"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content())
                .contains("OS")
                .contains("CPU")
                .contains("Memory")
                .contains("JVM");
    }

    @Test
    void osSection() {
        ToolResult result = tool.execute(Map.of("section", "os"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Name:");
    }

    @Test
    void jvmSection() {
        ToolResult result = tool.execute(Map.of("section", "jvm"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Java:");
    }
}
