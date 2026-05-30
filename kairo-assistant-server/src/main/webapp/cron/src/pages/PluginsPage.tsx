import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { pluginActionsApi, pluginsApi, type PluginView } from "../api/console";

export function PluginsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({
    queryKey: ["plugins"],
    queryFn: pluginsApi.list,
  });
  const plugins = (data?.items ?? []).slice().sort((a, b) => a.name.localeCompare(b.name));

  const invalidate = () => qc.invalidateQueries({ queryKey: ["plugins"] });

  const enable = useMutation({
    mutationFn: (id: string) => pluginActionsApi.enable(id),
    onSuccess: (r) =>
      r.error ? toast.error(r.error) : (toast.success("Enabled"), invalidate()),
  });
  const disable = useMutation({
    mutationFn: (id: string) => pluginActionsApi.disable(id),
    onSuccess: (r) =>
      r.error ? toast.error(r.error) : (toast.success("Disabled"), invalidate()),
  });
  const uninstall = useMutation({
    mutationFn: (id: string) => pluginActionsApi.uninstall(id),
    onSuccess: (r) =>
      r.error ? toast.error(r.error) : (toast.success("Uninstalled"), invalidate()),
  });

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Plugin Hub</h2>
        <p className="text-text-dim text-sm">
          Installed plugins providing skills, tools, hooks, MCP servers, or
          bin commands. Mirrors Claude Code's plugin format.
        </p>
      </div>

      <InstallForm
        onDone={() => qc.invalidateQueries({ queryKey: ["plugins"] })}
      />

      {isLoading && <div className="text-text-dim text-sm">Loading plugins…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load plugins: {(error as Error).message}
        </div>
      )}
      {!isLoading && !error && plugins.length === 0 && (
        <div className="text-text-dim text-sm border border-border rounded p-6 text-center">
          No plugins installed. Use the form above to install from GitHub, or
          run <code className="font-mono">/plugin install …</code> in the REPL.
        </div>
      )}

      {plugins.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {plugins.map((p) => (
            <PluginCard
              key={p.id}
              plugin={p}
              busy={enable.isPending || disable.isPending || uninstall.isPending}
              onEnable={() => enable.mutate(p.id)}
              onDisable={() => disable.mutate(p.id)}
              onUninstall={() => {
                if (confirm(`Uninstall ${p.name}?`)) uninstall.mutate(p.id);
              }}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function InstallForm({ onDone }: { onDone: () => void }) {
  const [ownerRepo, setOwnerRepo] = useState("");
  const [ref, setRef] = useState("");
  const [open, setOpen] = useState(false);

  const install = useMutation({
    mutationFn: () => pluginActionsApi.installGitHub(ownerRepo.trim(), ref.trim() || undefined),
    onSuccess: (r) => {
      if (r.error) toast.error(`Install failed: ${r.error}`);
      else {
        toast.success(`Installed ${r.name ?? r.id}`);
        setOwnerRepo("");
        setRef("");
        setOpen(false);
        onDone();
      }
    },
    onError: (e: Error) => toast.error(`Install failed: ${e.message}`),
  });

  return (
    <div className="mb-4 border border-border rounded bg-surface">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="w-full text-left px-4 py-2 text-sm font-semibold flex items-center justify-between"
      >
        Install from GitHub
        <span className="text-text-dim text-xs">{open ? "▴" : "▾"}</span>
      </button>
      {open && (
        <form
          className="px-4 pb-4 space-y-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (!ownerRepo.trim()) return;
            install.mutate();
          }}
        >
          <div className="grid grid-cols-1 md:grid-cols-[2fr_1fr] gap-2">
            <label className="block text-xs">
              <span className="text-text-dim uppercase tracking-wider">owner/repo</span>
              <input
                value={ownerRepo}
                onChange={(e) => setOwnerRepo(e.target.value)}
                placeholder="anthropics/claude-plugins-official"
                className="mt-1 w-full bg-bg border border-border rounded px-2 py-1 text-sm font-mono"
                required
              />
            </label>
            <label className="block text-xs">
              <span className="text-text-dim uppercase tracking-wider">ref (branch/tag)</span>
              <input
                value={ref}
                onChange={(e) => setRef(e.target.value)}
                placeholder="main"
                className="mt-1 w-full bg-bg border border-border rounded px-2 py-1 text-sm font-mono"
              />
            </label>
          </div>
          <div>
            <button
              type="submit"
              disabled={install.isPending || !ownerRepo.trim()}
              className="px-3 py-1.5 bg-accent text-text rounded text-xs hover:bg-accent-hover disabled:opacity-50"
            >
              {install.isPending ? "Installing…" : "Install"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

function PluginCard({
  plugin,
  busy,
  onEnable,
  onDisable,
  onUninstall,
}: {
  plugin: PluginView;
  busy: boolean;
  onEnable: () => void;
  onDisable: () => void;
  onUninstall: () => void;
}) {
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
      <div className="text-xs text-text-dim mb-2 font-mono">v{plugin.version}</div>
      {plugin.description && (
        <p className="text-sm text-text-dim mb-3 leading-snug">{plugin.description}</p>
      )}
      <div className="flex gap-2 text-[10px] uppercase tracking-wider mb-3">
        <span className="px-1.5 py-0.5 border border-border rounded text-text-dim">
          source: {plugin.source}
        </span>
        <span className="px-1.5 py-0.5 border border-border rounded text-text-dim">
          scope: {plugin.scope}
        </span>
      </div>
      <div className="flex gap-2 text-xs">
        {plugin.enabled ? (
          <button
            type="button"
            disabled={busy}
            onClick={onDisable}
            className="px-2 py-1 border border-border rounded text-text-dim hover:text-text hover:bg-primary/40 disabled:opacity-50"
          >
            Disable
          </button>
        ) : (
          <button
            type="button"
            disabled={busy}
            onClick={onEnable}
            className="px-2 py-1 border border-border rounded text-success hover:bg-success/20 disabled:opacity-50"
          >
            Enable
          </button>
        )}
        <button
          type="button"
          disabled={busy}
          onClick={onUninstall}
          className="ml-auto px-2 py-1 border border-border rounded text-red-300 hover:bg-red-500/20 disabled:opacity-50"
        >
          Uninstall
        </button>
      </div>
    </div>
  );
}
