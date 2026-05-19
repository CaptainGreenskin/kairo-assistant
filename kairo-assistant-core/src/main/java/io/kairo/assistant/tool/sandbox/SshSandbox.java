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

public class SshSandbox implements SandboxBackend {

    private static final Logger log = LoggerFactory.getLogger(SshSandbox.class);

    private final String host;
    private final int port;
    private final String user;
    private final String identityFile;

    public SshSandbox(String host, int port, String user, String identityFile) {
        this.host = host;
        this.port = port > 0 ? port : 22;
        this.user = user != null ? user : "root";
        this.identityFile = identityFile;
    }

    @Override
    public SandboxResult execute(String command, int timeoutSeconds, String workingDir) {
        List<String> sshCmd = new ArrayList<>();
        sshCmd.add("ssh");
        sshCmd.add("-o");
        sshCmd.add("StrictHostKeyChecking=no");
        sshCmd.add("-o");
        sshCmd.add("ConnectTimeout=10");
        sshCmd.add("-p");
        sshCmd.add(String.valueOf(port));

        if (identityFile != null && !identityFile.isBlank()) {
            sshCmd.add("-i");
            sshCmd.add(identityFile);
        }

        sshCmd.add(user + "@" + host);

        String fullCommand = workingDir != null && !workingDir.isBlank()
                ? "cd " + workingDir + " && " + command
                : command;
        sshCmd.add(fullCommand);

        try {
            ProcessBuilder pb = new ProcessBuilder(sshCmd).redirectErrorStream(true);
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

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new SandboxResult(-1, output.toString().trim(), true);
            }

            return new SandboxResult(process.exitValue(), output.toString().trim(), false);
        } catch (Exception e) {
            log.error("SSH sandbox execution failed: {}", e.getMessage());
            return new SandboxResult(-1, "SSH execution failed: " + e.getMessage(), false);
        }
    }

    @Override
    public String type() {
        return "ssh";
    }
}
