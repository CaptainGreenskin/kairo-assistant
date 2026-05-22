import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { toolHistoryApi, type ToolHistoryEntry } from "../api/console";

type StatusFilter = "all" | "success" | "error";

export function ToolHistoryPage() {
  const [filter, setFilter] = useState<StatusFilter>("all");
  const [toolFilter, setToolFilter] = useState("");
  const { data, isLoading, error } = useQuery({
    queryKey: ["tool-history"],
    queryFn: () => toolHistoryApi.list(100),
    refetchInterval: 5_000,
  });

  const calls = data?.calls ?? [];
  const filtered = useMemo(() => {
    return calls
      .filter((c) => {
        if (filter === "success" && !c.success) return false;
        if (filter === "error" && c.success) return false;
        if (toolFilter && !c.tool.toLowerCase().includes(toolFilter.toLowerCase())) return false;
        return true;
      })
      .slice() // copy before sorting
      .reverse(); // newest first
  }, [calls, filter, toolFilter]);

  return (
    <div className="p-6 max-w-6xl mx-auto">
      <div className="flex items-baseline justify-between mb-4">
        <div>
          <h2 className="text-lg font-semibold">Tool history</h2>
          <p className="text-text-dim text-sm">
            Recent tool invocations as recorded by ToolCallLogger. Refreshes every 5 seconds.
          </p>
        </div>
        {data && (
          <div className="text-xs text-text-dim">
            <span className="mr-3">{data.totalCalls.toLocaleString()} calls total</span>
            {data.totalErrors !== undefined && (
              <span className="mr-3 text-red-300">{data.totalErrors} errors</span>
            )}
            {data.avgDurationMs !== undefined && (
              <span>avg {data.avgDurationMs.toFixed(0)} ms</span>
            )}
          </div>
        )}
      </div>

      {data?.note && (
        <div className="text-xs text-warn mb-3 border border-warn/40 rounded p-2 bg-warn/10">
          {data.note}
        </div>
      )}

      <div className="flex gap-2 mb-3 text-xs">
        {(["all", "success", "error"] as const).map((f) => (
          <button
            key={f}
            type="button"
            onClick={() => setFilter(f)}
            className={[
              "px-2 py-1 rounded border",
              filter === f
                ? "bg-primary text-text border-border"
                : "border-border text-text-dim hover:text-text",
            ].join(" ")}
          >
            {f}
          </button>
        ))}
        <input
          type="search"
          value={toolFilter}
          onChange={(e) => setToolFilter(e.target.value)}
          placeholder="filter by tool name…"
          className="ml-auto px-2 py-1 bg-surface border border-border rounded w-56"
        />
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load history: {(error as Error).message}
        </div>
      )}

      {!isLoading && !error && (
        <div className="border border-border rounded bg-surface">
          {filtered.length === 0 && (
            <div className="text-center py-6 text-text-dim text-sm">
              No tool calls match the current filter.
            </div>
          )}
          {filtered.length > 0 && (
            <table className="w-full text-sm">
              <thead className="text-text-dim text-xs uppercase tracking-wider">
                <tr>
                  <th className="text-left px-3 py-2 w-8"></th>
                  <th className="text-left px-3 py-2">Tool</th>
                  <th className="text-right px-3 py-2">Duration</th>
                  <th className="text-left px-3 py-2">Timestamp</th>
                  <th className="text-left px-3 py-2">Error</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((c, i) => (
                  <Row key={i} call={c} />
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}

function Row({ call }: { call: ToolHistoryEntry }) {
  return (
    <tr className="border-t border-border hover:bg-primary/10">
      <td className="px-3 py-1.5">
        <span
          className={
            "inline-block w-2 h-2 rounded-full " +
            (call.success ? "bg-success" : "bg-red-400")
          }
          title={call.success ? "ok" : "error"}
        />
      </td>
      <td className="px-3 py-1.5 font-mono text-xs">{call.tool}</td>
      <td className="px-3 py-1.5 text-right text-text-dim tabular-nums">
        {call.durationMs} ms
      </td>
      <td className="px-3 py-1.5 text-text-dim text-xs">
        {new Date(call.timestamp).toLocaleString()}
      </td>
      <td className="px-3 py-1.5 text-red-300 text-xs truncate max-w-[280px]">
        {call.error ?? ""}
      </td>
    </tr>
  );
}
