import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { toolsApi, type ToolEntry } from "../api/console";

export function ToolsPage() {
  const [filter, setFilter] = useState("");
  const { data, isLoading, error } = useQuery({
    queryKey: ["tools"],
    queryFn: toolsApi.list,
  });

  // Backend returns either { total, tools: [...] } or a raw list — handle both.
  const rawTools: ToolEntry[] = Array.isArray(data)
    ? (data as ToolEntry[])
    : (data?.tools ?? []);

  const f = filter.trim().toLowerCase();
  const filtered = f
    ? rawTools.filter((t) =>
        t.name.toLowerCase().includes(f) ||
        (t.description ?? "").toLowerCase().includes(f) ||
        (t.category ?? "").toLowerCase().includes(f),
      )
    : rawTools;

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="flex items-baseline justify-between mb-4">
        <div>
          <h2 className="text-lg font-semibold">Tools</h2>
          <p className="text-text-dim text-sm">
            Every tool the agent can invoke. Side-effect badges mark which tools
            mutate state.
          </p>
        </div>
        <input
          type="search"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="filter by name, category, description…"
          className="px-2 py-1 text-xs bg-surface border border-border rounded w-64"
        />
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load tools: {(error as Error).message}
        </div>
      )}

      {filtered.length > 0 && (
        <div className="border border-border rounded bg-surface">
          <table className="w-full text-sm">
            <thead className="text-text-dim text-xs uppercase tracking-wider">
              <tr>
                <th className="text-left px-3 py-2">Name</th>
                <th className="text-left px-3 py-2">Category</th>
                <th className="text-left px-3 py-2">Side effect</th>
                <th className="text-left px-3 py-2">Description</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((t) => (
                <tr key={t.name} className="border-t border-border hover:bg-primary/10">
                  <td className="px-3 py-2 font-mono text-xs">{t.name}</td>
                  <td className="px-3 py-2 text-xs text-text-dim">{t.category ?? "—"}</td>
                  <td className="px-3 py-2">
                    {t.sideEffect && <SideEffectBadge value={String(t.sideEffect)} />}
                  </td>
                  <td className="px-3 py-2 text-xs text-text-dim">{t.description ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!isLoading && filtered.length === 0 && (
        <div className="text-center py-6 text-text-dim text-sm border border-border rounded">
          No tools matched.
        </div>
      )}
    </div>
  );
}

function SideEffectBadge({ value }: { value: string }) {
  const tone =
    value === "READ"
      ? "bg-green-500/20 text-green-300"
      : value === "WRITE"
      ? "bg-yellow-500/20 text-yellow-300"
      : value === "DESTRUCTIVE"
      ? "bg-red-500/20 text-red-300"
      : "bg-text-dim/20 text-text-dim";
  return (
    <span className={`text-[10px] px-1.5 py-0.5 rounded ${tone}`}>{value}</span>
  );
}
