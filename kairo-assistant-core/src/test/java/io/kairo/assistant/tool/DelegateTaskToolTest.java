package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DelegateTaskToolTest {

    private final DelegateTaskTool tool = new DelegateTaskTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void taskRequired() {
        ToolContext withDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of("context", "some context"), withDeps).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("task");
    }

    @Test
    void blankTaskErrors() {
        ToolContext withDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of("task", "  "), withDeps).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("task");
    }

    @Test
    void noModelProviderErrors() {
        ToolContext withDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of("task", "Do something"), withDeps).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("ModelProvider");
    }

    @Test
    void nullDependenciesErrors() {
        ToolResult r = tool.execute(Map.of("task", "Do something"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void emptyDepsNoModelProviderErrors() {
        ToolContext withDeps = new ToolContext("a", "s", Map.of());
        ToolResult r = tool.execute(Map.of("task", "Do it"), withDeps).block();
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("ModelProvider");
    }

    @Test
    void modelNameMissingErrors() {
        ToolContext partial = new ToolContext("a", "s", Map.of("modelProvider", "fake"));
        ToolResult r = tool.execute(Map.of("task", "Do it"), partial).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void hasInputSchema() {
        var schema = tool.inputSchema();
        assertThat(schema.properties()).containsKey("task");
        assertThat(schema.properties()).containsKey("context");
        assertThat(schema.properties()).containsKey("maxIterations");
    }

    @Test
    void schemaRequiresTask() {
        assertThat(tool.inputSchema().required()).containsExactly("task");
    }

    @Test
    void toolAnnotation() {
        var annotation = DelegateTaskTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("delegate_task");
        assertThat(annotation.category()).isEqualTo(io.kairo.api.tool.ToolCategory.AGENT_AND_TASK);
        assertThat(annotation.timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void sideEffectIsWrite() {
        var annotation = DelegateTaskTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(annotation.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.WRITE);
    }
}
