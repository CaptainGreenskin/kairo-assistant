package io.kairo.assistant.guardrail;

import io.kairo.api.guardrail.GuardrailContext;
import io.kairo.api.guardrail.GuardrailDecision;
import io.kairo.api.guardrail.GuardrailPayload;
import io.kairo.api.guardrail.GuardrailPhase;
import io.kairo.api.guardrail.GuardrailPolicy;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

public class PathTraversalPolicy implements GuardrailPolicy {

    private static final String NAME = "PathTraversalPolicy";

    private static final List<String> PATH_ARG_KEYS =
            List.of("file_path", "filePath", "path", "directory", "dir", "target");

    private static final Set<String> FILE_TOOLS = Set.of(
            "read_file", "write_file", "patch", "list_directory", "search_files");

    private static final Set<String> SENSITIVE_PATHS = Set.of(
            "/etc/passwd", "/etc/shadow", "/etc/sudoers",
            "/root/.ssh", "/root/.bashrc", "/root/.bash_history");

    private final Path allowedRoot;

    public PathTraversalPolicy(Path allowedRoot) {
        this.allowedRoot = allowedRoot != null ? allowedRoot.toAbsolutePath().normalize() : null;
    }

    public PathTraversalPolicy() {
        this(null);
    }

    @Override
    public Mono<GuardrailDecision> evaluate(GuardrailContext context) {
        if (context.phase() != GuardrailPhase.PRE_TOOL) {
            return Mono.just(GuardrailDecision.allow(NAME));
        }

        if (!(context.payload() instanceof GuardrailPayload.ToolInput toolInput)) {
            return Mono.just(GuardrailDecision.allow(NAME));
        }

        if (!FILE_TOOLS.contains(toolInput.toolName())) {
            return Mono.just(GuardrailDecision.allow(NAME));
        }

        String filePath = extractPath(toolInput.args());
        if (filePath == null || filePath.isBlank()) {
            return Mono.just(GuardrailDecision.allow(NAME));
        }

        if (hasTraversalComponent(filePath)) {
            return Mono.just(GuardrailDecision.deny(
                    "Path traversal detected in: " + filePath, NAME));
        }

        try {
            Path resolved = Path.of(filePath).toAbsolutePath().normalize();
            String normalizedStr = resolved.toString();

            for (String sensitive : SENSITIVE_PATHS) {
                if (normalizedStr.startsWith(sensitive)) {
                    return Mono.just(GuardrailDecision.deny(
                            "Access to sensitive path blocked: " + sensitive, NAME));
                }
            }

            if (allowedRoot != null && !resolved.startsWith(allowedRoot)) {
                return Mono.just(GuardrailDecision.deny(
                        "Path outside allowed workspace: " + filePath, NAME));
            }
        } catch (InvalidPathException e) {
            return Mono.just(GuardrailDecision.deny(
                    "Invalid path: " + filePath, NAME));
        }

        return Mono.just(GuardrailDecision.allow(NAME));
    }

    @Override
    public int order() {
        return -85;
    }

    @Override
    public String name() {
        return NAME;
    }

    static boolean hasTraversalComponent(String path) {
        String[] segments = path.split("[/\\\\]");
        for (String segment : segments) {
            if ("..".equals(segment.trim())) return true;
        }
        return false;
    }

    private String extractPath(Map<String, Object> args) {
        if (args == null) return null;
        for (String key : PATH_ARG_KEYS) {
            Object val = args.get(key);
            if (val instanceof String s) return s;
        }
        return null;
    }
}
