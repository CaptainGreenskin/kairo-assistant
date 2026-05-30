import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { toolsApi, toolExecuteApi, type ToolEntry } from "../api/console";

const DESTRUCTIVE = new Set(["WRITE", "DESTRUCTIVE"]);

export function ToolPlaygroundPage() {
  const [filter, setFilter] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [argsText, setArgsText] = useState("{}");
  const [argsError, setArgsError] = useState<string | null>(null);

  const tools = useQuery({
    queryKey: ["tools"],
    queryFn: toolsApi.list,
  });

  const rawTools: ToolEntry[] = Array.isArray(tools.data)
    ? (tools.data as ToolEntry[])
    : (tools.data?.items ?? []);
  const f = filter.trim().toLowerCase();
  const filtered = f
    ? rawTools.filter((t) =>
        t.name.toLowerCase().includes(f) ||
        (t.description ?? "").toLowerCase().includes(f),
      )
    : rawTools;

  const selectedTool = useMemo(
    () => rawTools.find((t) => t.name === selected) ?? null,
    [rawTools, selected],
  );
  const isDestructive =
    selectedTool?.sideEffect && DESTRUCTIVE.has(String(selectedTool.sideEffect));

  const run = useMutation({
    mutationFn: async () => {
      let parsed: Record<string, unknown>;
      try {
        parsed = JSON.parse(argsText || "{}");
      } catch (e) {
        throw new Error("Invalid JSON: " + (e as Error).message);
      }
      return toolExecuteApi.run(selected!, parsed);
    },
    onSuccess: (r) => {
      if (r.error) toast.error(`Tool error: ${r.error}`);
      else toast.success(`Ran ${r.tool}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <div className="mb-4">
        <h2 className="text-lg font-semibold">Tool playground</h2>
        <p className="text-text-dim text-sm">
          Manually invoke any registered tool with arbitrary JSON args. Useful
          for testing tools that the agent rarely calls or for reproducing bug
          reports. Destructive tools are flagged in red.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_2fr] gap-4">
        {/* Tool picker */}
        <div className="border border-border rounded bg-surface flex flex-col max-h-[70vh]">
          <div className="p-2 border-b border-border">
            <input
              type="search"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="filter tools…"
              className="w-full bg-bg border border-border rounded px-2 py-1 text-xs"
            />
          </div>
          <ul className="overflow-y-auto divide-y divide-border">
            {filtered.length === 0 && (
              <li className="p-4 text-text-dim text-sm">No matches.</li>
            )}
            {filtered.map((t) => (
              <li key={t.name}>
                <button
                  type="button"
                  onClick={() => {
                    setSelected(t.name);
                    run.reset();
                  }}
                  className={
                    "w-full text-left px-3 py-2 hover:bg-primary/20 " +
                    (selected === t.name ? "bg-primary/30" : "")
                  }
                >
                  <div className="flex items-baseline justify-between">
                    <span className="font-mono text-xs">{t.name}</span>
                    {t.sideEffect && (
                      <span
                        className={
                          "text-[9px] px-1 py-0.5 rounded " +
                          (DESTRUCTIVE.has(String(t.sideEffect))
                            ? "bg-red-500/20 text-red-300"
                            : "bg-green-500/20 text-green-300")
                        }
                      >
                        {String(t.sideEffect)}
                      </span>
                    )}
                  </div>
                  {t.description && (
                    <div className="text-[10px] text-text-dim mt-0.5 line-clamp-2">
                      {t.description}
                    </div>
                  )}
                </button>
              </li>
            ))}
          </ul>
        </div>

        {/* Editor + result */}
        <div className="space-y-3">
          {!selected && (
            <div className="border border-border rounded bg-surface p-6 text-center text-text-dim text-sm">
              Pick a tool on the left to invoke it.
            </div>
          )}
          {selected && selectedTool && (
            <>
              <div className="border border-border rounded bg-surface p-4">
                <div className="flex items-baseline justify-between mb-2">
                  <h3 className="font-semibold font-mono">{selectedTool.name}</h3>
                  {isDestructive && (
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-red-500/20 text-red-300">
                      ⚠ destructive
                    </span>
                  )}
                </div>
                {selectedTool.description && (
                  <p className="text-sm text-text-dim mb-3 leading-snug">
                    {selectedTool.description}
                  </p>
                )}
                <label className="block text-xs">
                  <span className="text-text-dim uppercase tracking-wider">Args (JSON)</span>
                  <textarea
                    value={argsText}
                    onChange={(e) => {
                      setArgsText(e.target.value);
                      try {
                        JSON.parse(e.target.value || "{}");
                        setArgsError(null);
                      } catch (err) {
                        setArgsError((err as Error).message);
                      }
                    }}
                    rows={8}
                    className="mt-1 w-full bg-bg border border-border rounded px-2 py-1 text-xs font-mono"
                  />
                </label>
                {argsError && (
                  <div className="text-[10px] text-red-300 mt-1 font-mono">
                    {argsError}
                  </div>
                )}
                <div className="flex gap-2 mt-3">
                  <button
                    type="button"
                    disabled={run.isPending || argsError !== null}
                    onClick={() => {
                      if (isDestructive && !confirm("Run a destructive tool?")) return;
                      run.mutate();
                    }}
                    className="px-3 py-1.5 bg-accent text-text rounded text-xs hover:bg-accent-hover disabled:opacity-50"
                  >
                    {run.isPending ? "Running…" : "Run"}
                  </button>
                </div>
              </div>

              {run.data && (
                <div className="border border-border rounded bg-surface">
                  <div className="px-3 py-2 border-b border-border flex items-baseline justify-between">
                    <span className="text-xs font-semibold uppercase tracking-wider">
                      Result
                    </span>
                    <span
                      className={
                        "text-[10px] px-1.5 py-0.5 rounded " +
                        (run.data.error || run.data.success === false
                          ? "bg-red-500/20 text-red-300"
                          : "bg-green-500/20 text-green-300")
                      }
                    >
                      {run.data.error || run.data.success === false ? "error" : "ok"}
                    </span>
                  </div>
                  <pre className="p-3 text-xs whitespace-pre-wrap break-words font-mono overflow-x-auto max-h-[40vh] overflow-y-auto">
                    {run.data.error
                      ? run.data.error
                      : typeof run.data.content === "string"
                        ? run.data.content
                        : JSON.stringify(run.data.content, null, 2)}
                  </pre>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
