package io.kairo.assistant.tool;

import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "system_info",
        description =
                "Get system information: OS details, CPU, memory, disk, JVM, and uptime.",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class SystemInfoTool implements SyncTool {

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("section", new JsonSchema("string", null, null,
                "Section: os, cpu, memory, disk, jvm, all. Default: all."));
        return new JsonSchema("object", props, List.of(), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String section = (String) args.getOrDefault("section", "all");
        StringBuilder sb = new StringBuilder();

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();

        boolean all = "all".equalsIgnoreCase(section);

        if (all || "os".equalsIgnoreCase(section)) {
            sb.append("=== OS ===\n");
            sb.append("Name: ").append(os.getName()).append("\n");
            sb.append("Version: ").append(os.getVersion()).append("\n");
            sb.append("Arch: ").append(os.getArch()).append("\n");
        }

        if (all || "cpu".equalsIgnoreCase(section)) {
            sb.append("=== CPU ===\n");
            sb.append("Processors: ").append(os.getAvailableProcessors()).append("\n");
            sb.append("Load avg: ").append(String.format("%.2f", os.getSystemLoadAverage())).append("\n");
        }

        if (all || "memory".equalsIgnoreCase(section)) {
            sb.append("=== Memory ===\n");
            long heapUsed = mem.getHeapMemoryUsage().getUsed() / (1024 * 1024);
            long heapMax = mem.getHeapMemoryUsage().getMax() / (1024 * 1024);
            sb.append("Heap: ").append(heapUsed).append("MB / ").append(heapMax).append("MB\n");
            long nonHeap = mem.getNonHeapMemoryUsage().getUsed() / (1024 * 1024);
            sb.append("Non-heap: ").append(nonHeap).append("MB\n");
            Runtime runtime = Runtime.getRuntime();
            sb.append("Total system memory: ").append(runtime.totalMemory() / (1024 * 1024)).append("MB\n");
            sb.append("Free system memory: ").append(runtime.freeMemory() / (1024 * 1024)).append("MB\n");
        }

        if (all || "disk".equalsIgnoreCase(section)) {
            sb.append("=== Disk ===\n");
            java.io.File root = new java.io.File("/");
            sb.append("Total: ").append(root.getTotalSpace() / (1024 * 1024 * 1024)).append("GB\n");
            sb.append("Free: ").append(root.getFreeSpace() / (1024 * 1024 * 1024)).append("GB\n");
            sb.append("Usable: ").append(root.getUsableSpace() / (1024 * 1024 * 1024)).append("GB\n");
        }

        if (all || "jvm".equalsIgnoreCase(section)) {
            sb.append("=== JVM ===\n");
            sb.append("Java: ").append(System.getProperty("java.version")).append("\n");
            sb.append("VM: ").append(rt.getVmName()).append(" ").append(rt.getVmVersion()).append("\n");
            long uptime = rt.getUptime() / 1000;
            sb.append("Uptime: ").append(uptime / 3600).append("h ")
                    .append((uptime % 3600) / 60).append("m ")
                    .append(uptime % 60).append("s\n");
        }

        return ToolResult.success("system_info", sb.toString().trim());
    }
}
