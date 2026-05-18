package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClarifyToolTest {

    private final ClarifyTool tool = new ClarifyTool();
    private final ToolContext ctx = new ToolContext("test", "s1", Map.of());

    @Test
    void requiresQuestion() {
        ToolResult result = tool.execute(Map.of(), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("question");
    }

    @Test
    void blankQuestionErrors() {
        ToolResult result = tool.execute(Map.of("question", "   "), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("question");
    }

    @Test
    void returnsFormattedQuestion() {
        ToolResult result = tool.execute(
                Map.of("question", "Which database should we use?"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("Question: Which database should we use?");
    }

    @Test
    void includesOptionsWhenProvided() {
        ToolResult result = tool.execute(Map.of(
                "question", "Pick a color",
                "options", "Red, Blue, Green"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("1. Red");
        assertThat(result.content()).contains("2. Blue");
        assertThat(result.content()).contains("3. Green");
    }

    @Test
    void singleOption() {
        ToolResult result = tool.execute(Map.of(
                "question", "Continue?",
                "options", "Yes"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("1. Yes");
    }

    @Test
    void includesContext() {
        ToolResult result = tool.execute(Map.of(
                "question", "Should we proceed?",
                "context", "The migration will affect 5M rows"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("Context: The migration will affect 5M rows");
        assertThat(result.content()).contains("Question: Should we proceed?");
    }

    @Test
    void contextAndOptionsTogether() {
        ToolResult result = tool.execute(Map.of(
                "question", "Which approach?",
                "context", "Performance is critical",
                "options", "Redis, Memcached"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Context: Performance is critical");
        assertThat(result.content()).contains("Question: Which approach?");
        assertThat(result.content()).contains("1. Redis");
        assertThat(result.content()).contains("2. Memcached");
    }

    @Test
    void metadataHasClarificationType() {
        ToolResult result = tool.execute(
                Map.of("question", "What format?"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("type", "clarification_request");
        assertThat(result.metadata()).containsEntry("question", "What format?");
    }

    @Test
    void metadataIncludesOptionsWhenProvided() {
        ToolResult result = tool.execute(Map.of(
                "question", "Which?",
                "options", "A, B"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("options", "A, B");
    }

    @Test
    void noOptionsInMetadataWhenOmitted() {
        ToolResult result = tool.execute(
                Map.of("question", "How?"), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.metadata()).doesNotContainKey("options");
    }

    @Test
    void blankContextIsIgnored() {
        ToolResult result = tool.execute(Map.of(
                "question", "Yes or no?",
                "context", "   "), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).doesNotContain("Context:");
    }

    @Test
    void blankOptionsIsIgnored() {
        ToolResult result = tool.execute(Map.of(
                "question", "Confirm?",
                "options", "  "), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.content()).doesNotContain("Options:");
    }

    @Test
    void inputSchemaFields() {
        var schema = tool.inputSchema();
        assertThat(schema.required()).contains("question");
        assertThat(schema.properties()).containsKey("options");
        assertThat(schema.properties()).containsKey("context");
    }

    @Test
    void toolAnnotation() {
        var ann = ClarifyTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(ann).isNotNull();
        assertThat(ann.name()).isEqualTo("clarify");
        assertThat(ann.category()).isEqualTo(io.kairo.api.tool.ToolCategory.AGENT_AND_TASK);
        assertThat(ann.sideEffect()).isEqualTo(io.kairo.api.tool.ToolSideEffect.READ_ONLY);
    }
}
