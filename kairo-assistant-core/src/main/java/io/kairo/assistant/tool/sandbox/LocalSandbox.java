package io.kairo.assistant.tool.sandbox;

import io.kairo.assistant.tool.ToolLimits;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class LocalSandbox implements SandboxBackend {

    @Override
    public SandboxResult execute(String command, int timeoutSeconds, String workingDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true);
            if (workingDir != null && !workingDir.isBlank()) {
                pb.directory(new File(workingDir));
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int charCount = 0;
                    while ((line = br.readLine()) != null
                            && charCount < ToolLimits.MAX_OUTPUT_CHARS) {
                        output.append(line).append("\n");
                        charCount += line.length();
                    }
                    if (charCount >= ToolLimits.MAX_OUTPUT_CHARS) {
                        output.append("\n... (output truncated at ")
                                .append(ToolLimits.MAX_OUTPUT_CHARS / 1000).append("K chars)");
                    }
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(2000);
                return new SandboxResult(-1, output.toString().trim(), true);
            }

            reader.join(5000);
            return new SandboxResult(process.exitValue(), output.toString().trim(), false);
        } catch (Exception e) {
            return new SandboxResult(-1, "Execution failed: " + e.getMessage(), false);
        }
    }

    @Override
    public String type() {
        return "local";
    }
}
