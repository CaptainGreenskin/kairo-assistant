import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { conversationsApi, replayApi } from "../api/console";

/**
 * Hermes Replay tab — exports a conversation session as a redacted JSON /
 * Markdown / HTML artifact for sharing in issues, PRs, or attached to a
 * support ticket. Redaction is server-side (SessionRedactor) and aggressive:
 * api keys, emails, absolute paths, JWTs, and UUIDs are scrubbed before the
 * bytes leave the box.
 */
export function ReplayPage() {
  const [selected, setSelected] = useState<string | null>(null);
  const sessions = useQuery({
    queryKey: ["conversations"],
    queryFn: () => conversationsApi.list(),
  });
  const preview = useQuery({
    queryKey: ["replay", selected],
    queryFn: () => replayApi.preview(selected!),
    enabled: selected !== null,
  });

  const list = sessions.data?.items ?? [];

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Replay</h2>
        <p className="text-text-dim text-sm">
          Redacted, shareable session exports — "Safe Share Mode" by default
          (api keys, emails, paths, JWTs, UUIDs scrubbed). Pick a session, preview
          the redacted output, then download as JSON / Markdown / HTML.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_2fr] gap-4">
        {/* Left — session picker */}
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

        {/* Right — preview + download */}
        <div className="border border-border rounded bg-surface">
          {!selected && (
            <div className="p-6 text-text-dim text-sm text-center">
              Select a session to preview its redacted export.
            </div>
          )}
          {selected && (
            <>
              <div className="border-b border-border p-3 flex items-center justify-between gap-2 flex-wrap">
                <div className="min-w-0">
                  <div className="text-sm font-semibold truncate">
                    {preview.data?.title || "Untitled session"}
                  </div>
                  <div className="text-[10px] text-text-dim font-mono">{selected}</div>
                </div>
                <div className="flex gap-2 text-xs">
                  <DownloadButton sessionId={selected} format="json" />
                  <DownloadButton sessionId={selected} format="markdown" />
                  <DownloadButton sessionId={selected} format="html" />
                </div>
              </div>

              {preview.isLoading && (
                <div className="p-4 text-text-dim text-sm">Building redacted preview…</div>
              )}
              {preview.error && (
                <div className="p-4 text-red-400 text-sm">
                  Preview failed: {(preview.error as Error).message}
                </div>
              )}
              {preview.data && <PreviewBody data={preview.data} />}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function DownloadButton({
  sessionId,
  format,
}: {
  sessionId: string;
  format: "json" | "markdown" | "html";
}) {
  return (
    <a
      href={replayApi.downloadUrl(sessionId, format)}
      target="_blank"
      rel="noreferrer"
      download={`replay-${sessionId}.${format === "markdown" ? "md" : format}`}
      className="px-2 py-1 border border-border rounded text-text-dim hover:text-text hover:bg-primary/40"
    >
      ↓ {format}
    </a>
  );
}

function PreviewBody({
  data,
}: {
  data: import("../api/console").ReplayPreview;
}) {
  return (
    <div className="max-h-[60vh] overflow-y-auto">
      {data.note && (
        <div className="px-3 py-2 text-[10px] text-text-dim bg-bg/40 border-b border-border">
          {data.note}
        </div>
      )}
      <ul className="divide-y divide-border">
        {data.entries.map((entry, i) => (
          <ReplayEntry key={i} entry={entry} />
        ))}
      </ul>
    </div>
  );
}

function ReplayEntry({ entry }: { entry: Record<string, unknown> }) {
  const type = String(entry.type ?? "?");
  if (type !== "message") {
    return (
      <li className="p-3 text-xs text-text-dim italic">
        [{type}] {entry.timestamp ? String(entry.timestamp) : ""}
      </li>
    );
  }
  const role = String(entry.role ?? "?");
  const content = String(entry.content ?? "");
  const ts = entry.timestamp ? String(entry.timestamp) : "";
  const tone =
    role === "user"
      ? "text-accent"
      : role === "assistant"
        ? "text-success"
        : "text-text-dim";
  return (
    <li className="p-3">
      <div className="flex items-baseline justify-between mb-1">
        <span className={`text-[10px] uppercase tracking-wider font-semibold ${tone}`}>
          {role}
        </span>
        {ts && <span className="text-[10px] text-text-dim font-mono">{ts}</span>}
      </div>
      <pre className="text-xs whitespace-pre-wrap break-words text-text/90 font-sans leading-relaxed">
        {content}
      </pre>
    </li>
  );
}
