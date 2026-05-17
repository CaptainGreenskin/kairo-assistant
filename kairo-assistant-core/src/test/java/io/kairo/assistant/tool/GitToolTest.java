package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitToolTest {

    private final GitTool tool = new GitTool();
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @Test
    void statusInCurrentDir() {
        ToolResult r = tool.execute(Map.of("action", "status"), ctx).block();
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
    }

    @Test
    void logShowsCommits() {
        ToolResult r = tool.execute(Map.of("action", "log", "args", "-n 3"), ctx).block();
        assertThat(r).isNotNull();
    }

    @Test
    void branchListsAll() {
        ToolResult r = tool.execute(Map.of("action", "branch"), ctx).block();
        assertThat(r).isNotNull();
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = tool.execute(Map.of("action", "push"), ctx).block();
        assertThat(r.isError()).isTrue();
    }

    @Test
    void actionRequired() {
        ToolResult r = tool.execute(Map.of("directory", "."), ctx).block();
        assertThat(r.isError()).isTrue();
    }
}
