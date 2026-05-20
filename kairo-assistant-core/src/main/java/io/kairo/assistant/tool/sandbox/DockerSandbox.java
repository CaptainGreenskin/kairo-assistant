package io.kairo.assistant.tool.sandbox;

import io.kairo.assistant.tool.ToolLimits;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DockerSandbox implements SandboxBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    private final String image;
    private final String workspaceMount;
    private final String networkMode;

    public DockerSandbox(String image, String workspaceMount, String networkMode) {
        this.image = image != null ? image : "ubuntu:22.04";
        this.workspaceMount = workspaceMount;
        this.networkMode = networkMode != null ? networkMode : "none";
    }

    @Override
    public SandboxResult execute(String command, int timeoutSeconds, String workingDir) {
        List<String> dockerCmd = new ArrayList<>();
        dockerCmd.add("docker");
        dockerCmd.add("run");
        dockerCmd.add("--rm");
        dockerCmd.add("--network=" + networkMode);
        dockerCmd.add("--memory=512m");
        dockerCmd.add("--cpus=1");
        dockerCmd.add("--pids-limit=256");
        dockerCmd.add("--read-only");
        dockerCmd.add("--tmpfs=/tmp:size=100m");

        if (workspaceMount != null && !workspaceMount.isBlank()) {
            dockerCmd.add("-v");
            dockerCmd.add(workspaceMount + ":/workspace");
            dockerCmd.add("-w");
            dockerCmd.add("/workspace");
        } else if (workingDir != null && !workingDir.isBlank()) {
            dockerCmd.add("-v");
            dockerCmd.add(workingDir + ":/workspace:ro");
            dockerCmd.add("-w");
            dockerCmd.add("/workspace");
        }

        dockerCmd.add(image);
        dockerCmd.add("sh");
        dockerCmd.add("-c");
        dockerCmd.add(command);

        try {
            ProcessBuilder pb = new ProcessBuilder(dockerCmd).redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int charCount = 0;
                while ((line = reader.readLine()) != null && charCount < ToolLimits.MAX_OUTPUT_CHARS) {
                    output.append(line).append("\n");
                    charCount += line.length();
                }
                if (charCount >= ToolLimits.MAX_OUTPUT_CHARS) {
                    output.append("\n... (output truncated)");
                }
            }

            boolean finished = process.waitFor(timeoutSeconds + 5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new SandboxResult(-1, output.toString().trim(), true);
            }

            return new SandboxResult(process.exitValue(), output.toString().trim(), false);
        } catch (Exception e) {
            log.error("Docker sandbox execution failed: {}", e.getMessage());
            return new SandboxResult(-1, "Docker execution failed: " + e.getMessage(), false);
        }
    }

    @Override
    public String type() {
        return "docker";
    }
}
