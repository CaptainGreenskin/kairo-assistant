package io.kairo.assistant.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SandboxTest {

    @Test
    void localSandboxExecutesCommand() {
        LocalSandbox sandbox = new LocalSandbox();
        SandboxBackend.SandboxResult result = sandbox.execute("echo hello", 10, null);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.output()).contains("hello");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.success()).isTrue();
    }

    @Test
    void localSandboxCapturesExitCode() {
        LocalSandbox sandbox = new LocalSandbox();
        SandboxBackend.SandboxResult result = sandbox.execute("exit 42", 10, null);

        assertThat(result.exitCode()).isEqualTo(42);
        assertThat(result.success()).isFalse();
    }

    @Test
    void localSandboxTimeout() {
        LocalSandbox sandbox = new LocalSandbox();
        SandboxBackend.SandboxResult result = sandbox.execute("sleep 30", 1, null);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void localSandboxWithWorkingDir() {
        LocalSandbox sandbox = new LocalSandbox();
        SandboxBackend.SandboxResult result = sandbox.execute("pwd", 10, "/tmp");

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.output()).contains("/tmp");
    }

    @Test
    void localSandboxType() {
        assertThat(new LocalSandbox().type()).isEqualTo("local");
    }

    @Test
    void dockerSandboxType() {
        DockerSandbox docker = new DockerSandbox("alpine:latest", null, "none");
        assertThat(docker.type()).isEqualTo("docker");
    }

    @Test
    void sshSandboxType() {
        SshSandbox ssh = new SshSandbox("localhost", 22, "user", null);
        assertThat(ssh.type()).isEqualTo("ssh");
    }

    @Test
    void factoryDefaultsToLocal() {
        SandboxBackend backend = SandboxFactory.instance();
        assertThat(backend.type()).isEqualTo("local");
    }

    @Test
    void factorySetInstance() {
        SandboxBackend original = SandboxFactory.instance();
        try {
            DockerSandbox docker = new DockerSandbox("test", null, "host");
            SandboxFactory.setInstance(docker);
            assertThat(SandboxFactory.instance().type()).isEqualTo("docker");
        } finally {
            SandboxFactory.setInstance(original);
        }
    }
}
