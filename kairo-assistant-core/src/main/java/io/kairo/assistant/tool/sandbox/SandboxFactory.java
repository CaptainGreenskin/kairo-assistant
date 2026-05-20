package io.kairo.assistant.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SandboxFactory {

    private static final Logger log = LoggerFactory.getLogger(SandboxFactory.class);

    private static volatile SandboxBackend INSTANCE;

    private SandboxFactory() {}

    public static SandboxBackend instance() {
        if (INSTANCE == null) {
            synchronized (SandboxFactory.class) {
                if (INSTANCE == null) {
                    INSTANCE = create();
                }
            }
        }
        return INSTANCE;
    }

    public static void setInstance(SandboxBackend backend) {
        INSTANCE = backend;
        log.info("Sandbox backend set to: {}", backend.type());
    }

    private static SandboxBackend create() {
        String mode = System.getenv().getOrDefault("KAIRO_SANDBOX_MODE", "local");
        return switch (mode) {
            case "docker" -> {
                String image = System.getenv().getOrDefault("KAIRO_SANDBOX_IMAGE", "ubuntu:22.04");
                String mount = System.getenv("KAIRO_SANDBOX_WORKSPACE");
                String network = System.getenv().getOrDefault("KAIRO_SANDBOX_NETWORK", "none");
                log.info("Using Docker sandbox (image={}, network={})", image, network);
                yield new DockerSandbox(image, mount, network);
            }
            case "ssh" -> {
                String host = System.getenv("KAIRO_SANDBOX_SSH_HOST");
                int port = parseInt(System.getenv().getOrDefault("KAIRO_SANDBOX_SSH_PORT", "22"));
                String user = System.getenv().getOrDefault("KAIRO_SANDBOX_SSH_USER", "root");
                String key = System.getenv("KAIRO_SANDBOX_SSH_KEY");
                if (host == null || host.isBlank()) {
                    log.warn("KAIRO_SANDBOX_SSH_HOST not set, falling back to local");
                    yield new LocalSandbox();
                }
                log.info("Using SSH sandbox ({}@{}:{})", user, host, port);
                yield new SshSandbox(host, port, user, key);
            }
            default -> {
                log.info("Using local sandbox (no isolation)");
                yield new LocalSandbox();
            }
        };
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 22;
        }
    }
}
