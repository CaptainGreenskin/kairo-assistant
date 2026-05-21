import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { observabilityApi } from "../api/console";

/**
 * SRE-style observability snapshot. Three sub-panels:
 *   • Latency percentiles (p50/p95/p99 if the metrics collector exposes them)
 *   • Endpoint hit table (rank by call count, /api/* routes)
 *   • Raw Prometheus text dump (collapsible) — for piping into a real scraper
 */
export function ObservabilityPage() {
  const latency = useQuery({
    queryKey: ["obs", "latency"],
    queryFn: observabilityApi.latency,
    refetchInterval: 15_000,
  });
  const endpoints = useQuery({
    queryKey: ["obs", "endpoints"],
    queryFn: observabilityApi.endpoints,
    refetchInterval: 15_000,
  });
  const prom = useQuery({
    queryKey: ["obs", "metrics-text"],
    queryFn: observabilityApi.metricsText,
    refetchInterval: 30_000,
  });

  const topEndpoints = useMemo(() => {
    const map = endpoints.data?.endpoints ?? {};
    return Object.entries(map).sort((a, b) => b[1] - a[1]).slice(0, 20);
  }, [endpoints.data]);

  const promLineCount = prom.data ? prom.data.split("\n").length : 0;

  return (
    <div className="p-6 max-w-6xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Observability</h2>
        <p className="text-text-dim text-sm">
          Latency percentiles + endpoint counters + raw Prometheus dump for
          scraping. All values refresh every 15-30 seconds.
        </p>
      </div>

      <section className="mb-6">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Latency</h3>
        {latency.isLoading && <div className="text-text-dim text-sm">Loading…</div>}
        {latency.data && (
          <>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
              <Stat label="Agent calls" value={(latency.data.totalAgentCalls ?? 0).toLocaleString()} />
              <Stat
                label="Avg latency"
                value={`${latency.data.avgDurationMs ?? 0} ms`}
              />
              <Stat
                label="Total ms"
                value={(latency.data.totalDurationMs ?? 0).toLocaleString()}
              />
            </div>
            {latency.data.percentiles && Object.keys(latency.data.percentiles).length > 0 && (
              <div className="grid grid-cols-2 md:grid-cols-5 gap-2">
                {Object.entries(latency.data.percentiles)
                  .sort(([a], [b]) => a.localeCompare(b))
                  .map(([k, v]) => (
                    <Stat key={k} label={k} value={`${v} ms`} mono />
                  ))}
              </div>
            )}
          </>
        )}
      </section>

      <section className="mb-6">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">
          Top endpoints
        </h3>
        {endpoints.isLoading && <div className="text-text-dim text-sm">Loading…</div>}
        {topEndpoints.length === 0 && !endpoints.isLoading && (
          <div className="text-text-dim text-sm">
            No endpoint counters yet — hit some routes and refresh.
          </div>
        )}
        {topEndpoints.length > 0 && (
          <div className="border border-border rounded bg-surface">
            <table className="w-full text-sm">
              <thead className="text-text-dim text-xs uppercase tracking-wider">
                <tr>
                  <th className="text-left px-3 py-2">Endpoint</th>
                  <th className="text-right px-3 py-2">Hits</th>
                  <th className="text-right px-3 py-2 w-32">Share</th>
                </tr>
              </thead>
              <tbody>
                {(() => {
                  const total = topEndpoints.reduce((s, [, n]) => s + n, 0);
                  return topEndpoints.map(([path, count]) => (
                    <tr key={path} className="border-t border-border hover:bg-primary/10">
                      <td className="px-3 py-1.5 font-mono text-xs truncate max-w-[420px]">
                        {path}
                      </td>
                      <td className="px-3 py-1.5 text-right tabular-nums">
                        {count.toLocaleString()}
                      </td>
                      <td className="px-3 py-1.5">
                        <Bar pct={total > 0 ? count / total : 0} />
                      </td>
                    </tr>
                  ));
                })()}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <details className="border border-border rounded bg-surface">
        <summary className="cursor-pointer px-3 py-2 text-xs uppercase tracking-wider text-text-dim">
          Raw Prometheus dump ({promLineCount.toLocaleString()} lines)
        </summary>
        <pre className="px-3 py-2 text-xs font-mono whitespace-pre overflow-auto max-h-[50vh]">
          {prom.data ?? "(loading)"}
        </pre>
      </details>
    </div>
  );
}

function Stat({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="border border-border rounded p-3 bg-surface">
      <div className="text-xs uppercase tracking-wider text-text-dim mb-1">{label}</div>
      <div
        className={
          "text-lg font-semibold tabular-nums " + (mono ? "font-mono" : "")
        }
      >
        {value}
      </div>
    </div>
  );
}

function Bar({ pct }: { pct: number }) {
  const w = Math.max(2, Math.min(100, pct * 100));
  return (
    <div className="relative w-full h-2 bg-bg border border-border rounded">
      <div
        className="absolute inset-y-0 left-0 bg-accent rounded"
        style={{ width: `${w}%` }}
      />
    </div>
  );
}
