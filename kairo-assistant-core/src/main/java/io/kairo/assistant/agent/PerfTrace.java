package io.kairo.assistant.agent;

/**
 * Lightweight phase-timing trace for cold-start profiling. Emits one line per phase to stderr when
 * {@code KAIRO_PERF_TRACE=1} (env var) or {@code kairo.perf.trace=true} (system property) is set —
 * otherwise it's a zero-cost no-op.
 *
 * <p>The output goes to stderr because stdout is the ACP JSON-RPC transport; any stray bytes would
 * corrupt the protocol.
 */
final class PerfTrace {

    private static final boolean ENABLED =
            "1".equals(System.getenv("KAIRO_PERF_TRACE"))
                    || "true".equalsIgnoreCase(System.getProperty("kairo.perf.trace"));

    private final String label;
    private final long startNanos;
    private long lastMarkNanos;

    private PerfTrace(String label) {
        this.label = label;
        this.startNanos = System.nanoTime();
        this.lastMarkNanos = startNanos;
    }

    static PerfTrace start(String label) {
        PerfTrace t = new PerfTrace(label);
        if (ENABLED) {
            System.err.printf("[perf] %s start%n", label);
        }
        return t;
    }

    void mark(String phase) {
        if (!ENABLED) return;
        long now = System.nanoTime();
        long deltaMs = (now - lastMarkNanos) / 1_000_000L;
        long sinceStartMs = (now - startNanos) / 1_000_000L;
        System.err.printf("[perf] %s  +%4dms  (total %5dms)  %s%n", label, deltaMs, sinceStartMs, phase);
        lastMarkNanos = now;
    }

    void done() {
        if (!ENABLED) return;
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000L;
        System.err.printf("[perf] %s done in %dms%n", label, totalMs);
    }
}
