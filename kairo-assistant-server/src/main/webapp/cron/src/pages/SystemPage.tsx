import { useQuery } from "@tanstack/react-query";
import { systemApi } from "../api/console";

export function SystemPage() {
  const system = useQuery({
    queryKey: ["system"],
    queryFn: systemApi.info,
    refetchInterval: 30_000,
  });
  const agent = useQuery({
    queryKey: ["agent", "state"],
    queryFn: systemApi.agent,
    refetchInterval: 10_000,
  });

  const s = system.data;
  const a = agent.data;

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">System</h2>
        <p className="text-text-dim text-sm">
          JVM + OS environment of the assistant server, plus current agent state.
        </p>
      </div>

      <section className="mb-6">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Agent</h3>
        {a && (
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
            <Kv label="Name" value={a.agentName ?? "—"} />
            <Kv
              label="State"
              value={a.state ?? "—"}
              tone={a.state === "IDLE" ? "success" : a.state === "RUNNING" ? "warn" : undefined}
            />
            <Kv label="ID" value={a.agentId ?? "—"} mono />
          </div>
        )}
      </section>

      <section className="mb-6">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Runtime</h3>
        {s && (
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
            <Kv label="Java" value={`${s.javaVersion ?? "?"} (${s.javaVendor ?? "?"})`} />
            <Kv label="OS" value={`${s.os ?? "?"} ${s.osVersion ?? ""} (${s.arch ?? "?"})`} />
            <Kv label="CPUs" value={String(s.processors ?? "?")} />
            <Kv label="Threads" value={String(s.activeThreads ?? "?")} />
            <Kv label="File encoding" value={s.fileEncoding ?? "?"} />
          </div>
        )}
      </section>

      <section className="mb-6">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Heap</h3>
        {s && (
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
            <Kv label="Used (MB)" value={String(s.memoryUsedMB ?? "?")} />
            <Kv label="Total (MB)" value={String(s.memoryTotalMB ?? "?")} />
            <Kv label="Max (MB)" value={String(s.memoryMaxMB ?? "?")} />
          </div>
        )}
      </section>

      <section>
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Paths</h3>
        {s && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <Kv label="Working dir" value={s.userDir ?? "?"} mono />
            <Kv label="User home" value={s.userHome ?? "?"} mono />
          </div>
        )}
      </section>
    </div>
  );
}

function Kv({
  label,
  value,
  tone,
  mono,
}: {
  label: string;
  value: string;
  tone?: "success" | "warn";
  mono?: boolean;
}) {
  const v =
    tone === "success" ? "text-success" : tone === "warn" ? "text-yellow-300" : "text-text";
  return (
    <div className="border border-border rounded p-3 bg-surface">
      <div className="text-xs uppercase tracking-wider text-text-dim mb-1">{label}</div>
      <div className={`${mono ? "font-mono text-xs break-all" : "text-sm font-semibold"} ${v}`}>
        {value}
      </div>
    </div>
  );
}
