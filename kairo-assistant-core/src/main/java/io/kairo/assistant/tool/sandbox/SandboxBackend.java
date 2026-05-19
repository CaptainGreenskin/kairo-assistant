package io.kairo.assistant.tool.sandbox;

public interface SandboxBackend {

    SandboxResult execute(String command, int timeoutSeconds, String workingDir);

    String type();

    record SandboxResult(int exitCode, String output, boolean timedOut) {
        public boolean success() {
            return exitCode == 0 && !timedOut;
        }
    }
}
