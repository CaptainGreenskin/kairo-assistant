import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { conversationsApi, traceApi } from "../api/console";

/**
 * Lightweight session timeline. Not a full OTEL trace viewer — just a swim-lane
 * rendering of {session_start, message[user/assistant], session_end} entries
 * pulled from /api/sessions/{id}/export?format=json so operators can scan a
 * conversation's shape without piping JSON through an external viewer.
 */
interface TraceEntry {
  type?: string;
  role?: string;
  content?: string;
  timestamp?: string;
  [k: string]: unknown;
}

export function TracePage() {
  const [selected, setSelected] = useState<string | null>(null);
  const sessions = useQuery({
    queryKey: ["conversations"],
    queryFn: () => conversationsApi.list(),
  });

  const trace = useQuery({
    queryKey: ["trace", selected],
    queryFn: () => traceApi.exportJson(selected!),
    enabled: selected !== null,
  });

  const entries: TraceEntry[] = useMemo(() => {
    if (!trace.data?.content) return [];
    try {
      const parsed = JSON.parse(trace.data.content);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }, [trace.data]);

  const list = sessions.data?.conversations ?? [];

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Trace</h2>
        <p className="text-text-dim text-sm">
          Session timeline — every message + lifecycle event in chronological order.
          Pulled from the conversation log; same source as Replay but without
          redaction, so handle with care.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_2fr] gap-4">
        <div className="border border-border rounded bg-surface max-h-[70vh] overflow-y-auto">
          {sessions.isLoading && (
            <div className="p-4 text-text-dim text-sm">Loading sessions…</div>
          )}
          {!sessions.isLoading && list.length === 0 && (
            <div className="p-4 text-text-dim text-sm">No conversations yet.</div>
          )}
          <ul className="divide-y divide-border">
            {list.map((row) => {
              const id = String((row as { sessionId: string }).sessionId);
              const title = (row as { title?: string }).title;
              return (
                <li key={id}>
                  <button
                    type="button"
                    onClick={() => setSelected(id)}
                    className={
                      "w-full text-left px-3 py-2 hover:bg-primary/20 " +
                      (selected === id ? "bg-primary/30" : "")
                    }
                  >
                    <div className="text-sm font-medium truncate">
                      {title || id.slice(0, 16) + "…"}
                    </div>
                    <div className="text-[10px] text-text-dim font-mono mt-0.5">
                      {id.slice(0, 8)}
                    </div>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>

        <div className="border border-border rounded bg-surface min-h-[40vh] max-h-[70vh] overflow-y-auto">
          {!selected && (
            <div className="p-6 text-text-dim text-sm text-center">
              Select a session to see its timeline.
            </div>
          )}
          {selected && trace.isLoading && (
            <div className="p-4 text-text-dim text-sm">Loading trace…</div>
          )}
          {selected && entries.length > 0 && <Timeline entries={entries} />}
          {selected && !trace.isLoading && entries.length === 0 && (
            <div className="p-4 text-text-dim text-sm">
              Session is empty or could not be parsed.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function Timeline({ entries }: { entries: TraceEntry[] }) {
  const start = entries.find((e) => e.timestamp)?.timestamp;
  return (
    <ol className="relative border-l border-border ml-6 my-4">
      {entries.map((e, i) => (
        <TraceRow key={i} entry={e} index={i} startedAt={start} />
      ))}
    </ol>
  );
}

function TraceRow({
  entry,
  index,
  startedAt,
}: {
  entry: TraceEntry;
  index: number;
  startedAt: string | undefined;
}) {
  const type = entry.type ?? "?";
  const role = entry.role ?? type;
  const tone =
    role === "user"
      ? "bg-accent text-bg"
      : role === "assistant"
        ? "bg-success text-bg"
        : "bg-text-dim text-bg";
  const tsStr = entry.timestamp ? new Date(entry.timestamp).toLocaleTimeString() : "—";
  const delta =
    entry.timestamp && startedAt
      ? new Date(entry.timestamp).getTime() - new Date(startedAt).getTime()
      : null;

  return (
    <li className="ml-6 mb-4 relative">
      <span
        className={
          "absolute -left-[31px] top-0.5 w-4 h-4 rounded-full ring-2 ring-bg text-[8px] font-bold flex items-center justify-center " +
          tone
        }
      >
        {index + 1}
      </span>
      <div className="text-[10px] text-text-dim font-mono flex items-baseline gap-2">
        <span>{tsStr}</span>
        {delta !== null && (
          <span className="opacity-60">+{(delta / 1000).toFixed(1)}s</span>
        )}
        <span className="uppercase tracking-wider">{role}</span>
      </div>
      {entry.content && (
        <pre className="mt-1 text-xs whitespace-pre-wrap break-words text-text/90 font-sans leading-relaxed">
          {entry.content}
        </pre>
      )}
    </li>
  );
}
