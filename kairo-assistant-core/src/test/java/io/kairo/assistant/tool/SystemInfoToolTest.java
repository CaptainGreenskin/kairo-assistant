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
                .contains("JVM")
                .contains("Disk");
    }

    @Test
    void osSection() {
        ToolResult result = tool.execute(Map.of("section", "os"), ctx).block();
        assertThat(result.content()).contains("Name:");
        assertThat(result.content()).contains("Version:");
        assertThat(result.content()).contains("Arch:");
        assertThat(result.content()).doesNotContain("CPU");
    }

    @Test
    void cpuSection() {
        ToolResult result = tool.execute(Map.of("section", "cpu"), ctx).block();
        assertThat(result.content()).contains("Processors:");
        assertThat(result.content()).contains("Load avg:");
        assertThat(result.content()).doesNotContain("OS");
    }

    @Test
    void memorySection() {
        ToolResult result = tool.execute(Map.of("section", "memory"), ctx).block();
        assertThat(result.content()).contains("Heap:");
        assertThat(result.content()).contains("Non-heap:");
        assertThat(result.content()).doesNotContain("CPU");
    }

    @Test
    void diskSection() {
        ToolResult result = tool.execute(Map.of("section", "disk"), ctx).block();
        assertThat(result.content()).contains("Total:");
        assertThat(result.content()).contains("Free:");
        assertThat(result.content()).contains("Usable:");
        assertThat(result.content()).doesNotContain("OS");
    }

    @Test
    void jvmSection() {
        ToolResult result = tool.execute(Map.of("section", "jvm"), ctx).block();
        assertThat(result.content()).contains("Java:");
        assertThat(result.content()).contains("VM:");
        assertThat(result.content()).contains("Uptime:");
        assertThat(result.content()).doesNotContain("CPU");
    }

    @Test
    void defaultSectionIsAll() {
        ToolResult result = tool.execute(Map.of(), ctx).block();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("OS").contains("CPU").contains("JVM");
    }

    @Test
    void caseInsensitiveSection() {
        ToolResult result = tool.execute(Map.of("section", "CPU"), ctx).block();
        assertThat(result.content()).contains("Processors:");
    }

    @Test
    void schemaHasSectionField() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("section");
        assertThat(schema.required()).isEmpty();
    }

    @Test
    void resultIsNeverError() {
        for (String sec : new String[]{"os", "cpu", "memory", "disk", "jvm", "all"}) {
            ToolResult r = tool.execute(Map.of("section", sec), ctx).block();
            assertThat(r.isError()).as("Section '%s' should not error", sec).isFalse();
        }
    }
}
