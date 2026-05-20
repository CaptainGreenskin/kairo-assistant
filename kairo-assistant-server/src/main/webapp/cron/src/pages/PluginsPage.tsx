import { useQuery } from "@tanstack/react-query";
import { pluginsApi, type PluginView } from "../api/console";

export function PluginsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["plugins"],
    queryFn: pluginsApi.list,
  });

  const plugins = (data ?? []).slice().sort((a, b) => a.name.localeCompare(b.name));

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Plugin Hub</h2>
        <p className="text-text-dim text-sm">
          Installed plugins providing skills, tools, hooks, MCP servers, or
          bin commands. Mirrors Claude Code's plugin format.
        </p>
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading plugins…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load plugins: {(error as Error).message}
        </div>
      )}
      {!isLoading && !error && plugins.length === 0 && (
        <div className="text-text-dim text-sm border border-border rounded p-6 text-center">
          No plugins installed. See <code className="font-mono">/plugin install …</code> in the REPL.
        </div>
      )}

      {plugins.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {plugins.map((p) => (
            <PluginCard key={p.id} plugin={p} />
          ))}
        </div>
      )}
    </div>
  );
}

function PluginCard({ plugin }: { plugin: PluginView }) {
  return (
    <div className="border border-border rounded p-4 bg-surface">
      <div className="flex items-baseline justify-between mb-1">
        <h3 className="font-semibold">{plugin.name}</h3>
        <span
          className={
            "text-[10px] px-1.5 py-0.5 rounded " +
            (plugin.enabled
              ? "bg-green-500/20 text-green-300"
              : "bg-text-dim/20 text-text-dim")
          }
        >
          {plugin.enabled ? "enabled" : "disabled"}
        </span>
      </div>
      <div className="text-xs text-text-dim mb-2 font-mono">
        v{plugin.version}
      </div>
      {plugin.description && (
        <p className="text-sm text-text-dim mb-3 leading-snug">
          {plugin.description}
        </p>
      )}
      <div className="flex gap-2 text-[10px] uppercase tracking-wider">
        <span className="px-1.5 py-0.5 border border-border rounded text-text-dim">
          source: {plugin.source}
        </span>
        <span className="px-1.5 py-0.5 border border-border rounded text-text-dim">
          scope: {plugin.scope}
        </span>
      </div>
    </div>
  );
}
