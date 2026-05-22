import { useQuery } from "@tanstack/react-query";
import { analyticsApi } from "../api/console";

export function AnalyticsPage() {
  const overview = useQuery({
    queryKey: ["analytics", "overview"],
    queryFn: analyticsApi.overview,
    refetchInterval: 15_000,
  });
  const tokens = useQuery({
    queryKey: ["analytics", "tokens"],
    queryFn: analyticsApi.tokens,
    refetchInterval: 15_000,
  });
  const tools = useQuery({
    queryKey: ["analytics", "tools"],
    queryFn: analyticsApi.tools,
    refetchInterval: 15_000,
  });

  const o = overview.data;
  const t = tokens.data;
  const tl = tools.data;

  return (
    <div className="p-6 max-w-6xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Analytics</h2>
        <p className="text-text-dim text-sm">
          Tokens, tool calls, latency, endpoints. Pulled from the assistant's
          metrics collector. Refreshes every 15 seconds.
        </p>
      </div>

      <section className="mb-6">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Tokens</h3>
        {tokens.isLoading && <div className="text-text-dim text-sm">Loading…</div>}
        {t && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <Stat label="Input" value={t.inputTokens.toLocaleString()} />
            <Stat label="Output" value={t.outputTokens.toLocaleString()} />
            <Stat label="Total" value={t.totalTokens.toLocaleString()} />
            <Stat
              label="Est. cost (USD)"
              value={`$${t.estimatedCostUsd.toFixed(4)}`}
              tone="accent"
            />
          </div>
        )}
        {t?.pricing && (
          <div className="text-[10px] text-text-dim mt-2">
            Pricing: {t.pricing.model} · ${t.pricing.inputPer1MTokens}/1M in · $
            {t.pricing.outputPer1MTokens}/1M out
            {t.pricing.note && <span className="opacity-60"> · {t.pricing.note}</span>}
          </div>
        )}
      </section>

      <section className="mb-6">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Tool calls</h3>
        {tools.isLoading && <div className="text-text-dim text-sm">Loading…</div>}
        {tl && (
          <>
            <div className="grid grid-cols-2 md:grid-cols-3 gap-3 mb-3">
              <Stat label="Total calls" value={tl.totalToolCalls.toLocaleString()} />
              <Stat label="Unique tools" value={String(tl.uniqueToolsUsed)} />
            </div>
            {tl.tools && Object.keys(tl.tools).length > 0 && (
              <div className="border border-border rounded bg-surface">
                <table className="w-full text-sm">
                  <thead className="text-text-dim text-xs uppercase tracking-wider">
                    <tr>
                      <th className="text-left px-3 py-2">Tool</th>
                      <th className="text-right px-3 py-2">Calls</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(tl.tools)
                      .sort(([, a], [, b]) => b - a)
                      .map(([name, count]) => (
                        <tr key={name} className="border-t border-border">
                          <td className="px-3 py-1.5 font-mono text-xs">{name}</td>
                          <td className="px-3 py-1.5 text-right tabular-nums">
                            {count.toLocaleString()}
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </section>

      <section className="mb-6">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Overview</h3>
        {o && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <Stat label="Provider" value={o.provider ?? "—"} />
            <Stat label="Model" value={o.model ?? "—"} />
            <Stat label="Sessions" value={String(o.totalSessions ?? 0)} />
            <Stat label="Messages" value={String(o.totalMessages ?? 0)} />
            <Stat label="Registered tools" value={String(o.registeredTools ?? 0)} />
            <Stat label="Registered skills" value={String(o.registeredSkills ?? 0)} />
            <Stat label="Plugins" value={String(o.plugins ?? 0)} />
            <Stat label="Uptime (s)" value={String(o.uptime ?? 0)} />
          </div>
        )}
      </section>

      {o?.durationPercentiles && Object.keys(o.durationPercentiles).length > 0 && (
        <section>
          <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Latency (ms)</h3>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            {Object.entries(o.durationPercentiles).map(([k, v]) => (
              <Stat key={k} label={k} value={String(v)} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "accent";
}) {
  return (
    <div className="border border-border rounded p-3 bg-surface">
      <div className="text-xs uppercase tracking-wider text-text-dim mb-1">{label}</div>
      <div
        className={
          "text-lg font-semibold tabular-nums " + (tone === "accent" ? "text-accent" : "")
        }
      >
        {value}
      </div>
    </div>
  );
}
