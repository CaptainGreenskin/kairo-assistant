/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.server;

import io.kairo.api.plugin.PluginScope;
import io.kairo.api.plugin.PluginSource;
import io.kairo.assistant.agent.AssistantSession;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Mutating plugin endpoints — the read-only {@code GET /api/plugins} listing lives in
 * {@link StatusController} alongside the rest of the status surface.
 *
 * <p>Mutations are POST/DELETE on dedicated paths so the React UI can wire one button per action
 * without parsing extra JSON. Failures are translated to plain {@code {error: ...}} payloads (no
 * 4xx) to match the rest of the assistant's controller style.
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final AssistantSession session;

    public PluginController(AssistantSession session) {
        this.session = session;
    }

    @PostMapping("/{id}/enable")
    public Mono<Map<String, Object>> enable(@PathVariable String id) {
        return session.pluginManager()
                .enable(id)
                .thenReturn(Map.<String, Object>of("status", "enabled", "id", id))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage(), "id", id)));
    }

    @PostMapping("/{id}/disable")
    public Mono<Map<String, Object>> disable(@PathVariable String id) {
        return session.pluginManager()
                .disable(id)
                .thenReturn(Map.<String, Object>of("status", "disabled", "id", id))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage(), "id", id)));
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> uninstall(@PathVariable String id) {
        return session.pluginManager()
                .uninstall(id)
                .thenReturn(Map.<String, Object>of("status", "uninstalled", "id", id))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage(), "id", id)));
    }

    /**
     * Install a plugin from a {@code source} JSON object. Supports the three most common shapes:
     *
     * <pre>{@code
     * {"type": "github", "ownerRepo": "anthropics/x", "ref": "main"}
     * {"type": "path",   "path": "/abs/path/to/plugin"}
     * {"type": "git",    "url": "https://example.com/repo.git", "ref": "main"}
     * }</pre>
     */
    @PostMapping("/install")
    public Mono<Map<String, Object>> install(@RequestBody Map<String, Object> body) {
        Object sourceObj = body.get("source");
        if (!(sourceObj instanceof Map<?, ?> rawMap)) {
            return Mono.just(Map.of("error", "source object is required"));
        }
        PluginSource source;
        try {
            source = parseSource(rawMap);
        } catch (IllegalArgumentException e) {
            return Mono.just(Map.of("error", e.getMessage()));
        }
        PluginScope scope =
                PluginScope.valueOf(
                        String.valueOf(body.getOrDefault("scope", "USER"))
                                .toUpperCase(Locale.ROOT));
        return session.pluginManager()
                .install(source, scope)
                .map(inst -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "installed");
                    result.put("id", inst.id());
                    result.put("name", inst.metadata().name());
                    result.put("version", inst.metadata().version());
                    return result;
                })
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));
    }

    private static PluginSource parseSource(Map<?, ?> raw) {
        Object typeObj = raw.get("type");
        String type =
                typeObj == null
                        ? "github"
                        : String.valueOf(typeObj).toLowerCase(Locale.ROOT);
        return switch (type) {
            case "github" -> {
                String ownerRepo = stringOrThrow(raw, "ownerRepo");
                String ref = stringOrNull(raw, "ref");
                String sha = stringOrNull(raw, "sha");
                yield new PluginSource.GitHub(ownerRepo, ref, sha);
            }
            case "path", "local" -> new PluginSource.LocalPath(
                    java.nio.file.Path.of(stringOrThrow(raw, "path")));
            case "git" -> new PluginSource.GitUrl(
                    stringOrThrow(raw, "url"), stringOrNull(raw, "ref"));
            default -> throw new IllegalArgumentException("unsupported source type: " + type);
        };
    }

    private static String stringOrThrow(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("field '" + key + "' is required");
        }
        return String.valueOf(v);
    }

    private static String stringOrNull(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }
}
