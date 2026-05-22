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
          <>
            <div className="grid grid-cols-2 md:grid-cols-3 gap-3 mb-4">
              <Kv label="Name" value={a.agentName ?? "—"} />
              <Kv
                label="State"
                value={a.state ?? "—"}
                tone={a.state === "IDLE" ? "success" : a.state === "RUNNING" ? "warn" : undefined}
              />
              <Kv label="ID" value={a.agentId ?? "—"} mono />
            </div>
            <AgentStateMachine current={a.state ?? "UNKNOWN"} />
          </>
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

/**
 * Visual representation of the agent state machine. States are arranged in
 * lifecycle order; the currently-active state pulses + glows.
 *
 * State names follow io.kairo.api.agent.AgentState — keep in sync if you
 * add new states server-side (the diagram falls back to "unknown" for
 * states it doesn't recognise, so it never crashes).
 */
const STATES: Array<{ id: string; label: string; description: string }> = [
  { id: "IDLE", label: "IDLE", description: "Waiting for input" },
  { id: "REASONING", label: "REASONING", description: "Thinking about next step" },
  { id: "ACTING", label: "ACTING", description: "Invoking a tool" },
  { id: "WAITING", label: "WAITING", description: "Waiting on model / tool I/O" },
  { id: "DONE", label: "DONE", description: "Iteration complete" },
  { id: "ERROR", label: "ERROR", description: "Loop aborted" },
];

function AgentStateMachine({ current }: { current: string }) {
  const known = STATES.some((s) => s.id === current);
  return (
    <div className="border border-border rounded p-4 bg-surface">
      <div className="text-[10px] uppercase tracking-wider text-text-dim mb-3">
        Lifecycle
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {STATES.map((s, i) => {
          const active = s.id === current;
          const isError = s.id === "ERROR";
          return (
            <div key={s.id} className="flex items-center gap-2">
              <div
                title={s.description}
                className={[
                  "px-3 py-1.5 rounded-md text-xs font-semibold border transition-all",
                  active
                    ? isError
                      ? "bg-red-500/30 text-red-200 border-red-500/60 animate-pulse"
                      : "bg-accent/30 text-accent border-accent/60 animate-pulse shadow-lg shadow-accent/20"
                    : "bg-bg text-text-dim border-border",
                ].join(" ")}
              >
                {s.label}
              </div>
              {i < STATES.length - 1 && i < STATES.length - 2 ? (
                <span className="text-text-dim text-xs">→</span>
              ) : i === STATES.length - 2 ? (
                <span className="text-text-dim text-xs">⇄</span>
              ) : null}
            </div>
          );
        })}
      </div>
      {!known && (
        <div className="text-[10px] text-warn mt-3">
          Current state <code className="font-mono">{current}</code> is not in
          the known lifecycle — the server may have added a new AgentState
          enum value.
        </div>
      )}
      <div className="text-[10px] text-text-dim mt-3 leading-snug">
        Active state glows. ERROR is a sink — only reachable from REASONING/ACTING
        when the loop detector or circuit breaker trips. DONE returns to IDLE on
        the next user input.
      </div>
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
