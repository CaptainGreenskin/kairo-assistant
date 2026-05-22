import { useQuery } from "@tanstack/react-query";
import { healthApi } from "../api/console";

export function HealthPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["health", "detailed"],
    queryFn: healthApi.detailed,
    refetchInterval: 10_000,
  });

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Health</h2>
        <p className="text-text-dim text-sm">
          Server liveness, JVM memory, uptime. Refreshes every 10 seconds.
        </p>
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load health: {(error as Error).message}
        </div>
      )}

      {data && (
        <div className="space-y-6">
          <section>
            <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Server</h3>
            <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
              <Kv label="Status" value={data.status} ok={data.status === "ok"} />
              <Kv label="Uptime" value={data.uptime ?? `${data.uptimeSeconds ?? 0}s`} />
            </div>
          </section>

          {data.memory && (
            <section>
              <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Heap</h3>
              <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
                <Kv label="Used (MB)" value={String(data.memory.usedMB ?? data.memory.heapUsedMb ?? "—")} />
                <Kv label="Max (MB)" value={String(data.memory.maxMB ?? data.memory.heapMaxMb ?? "—")} />
                {(() => {
                  const used = (data.memory.usedMB ?? data.memory.heapUsedMb) as number | undefined;
                  const max = (data.memory.maxMB ?? data.memory.heapMaxMb) as number | undefined;
                  return used !== undefined && max !== undefined ? (
                    <Kv
                      label="Utilization"
                      value={`${((used / max) * 100).toFixed(0)}%`}
                    />
                  ) : null;
                })()}
              </div>
            </section>
          )}

          <RawJsonSection title="Raw response" data={data} />
        </div>
      )}
    </div>
  );
}

function Kv({ label, value, ok }: { label: string; value: string; ok?: boolean }) {
  return (
    <div className="border border-border rounded p-3 bg-surface">
      <div className="text-xs uppercase tracking-wider text-text-dim mb-1">{label}</div>
      <div
        className={
          "text-lg font-semibold " + (ok ? "text-success" : ok === false ? "text-red-400" : "")
        }
      >
        {value}
      </div>
    </div>
  );
}

function RawJsonSection({ title, data }: { title: string; data: unknown }) {
  return (
    <details className="border border-border rounded p-3 bg-surface">
      <summary className="cursor-pointer text-xs uppercase tracking-wider text-text-dim">
        {title}
      </summary>
      <pre className="mt-2 text-xs text-text-dim font-mono overflow-x-auto whitespace-pre-wrap">
        {JSON.stringify(data, null, 2)}
      </pre>
    </details>
  );
}
