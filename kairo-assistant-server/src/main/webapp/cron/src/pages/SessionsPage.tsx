import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { conversationsApi } from "../api/console";

export function SessionsPage() {
  const [selected, setSelected] = useState<string | null>(null);
  const [query, setQuery] = useState("");

  const list = useQuery({
    queryKey: ["conversations"],
    queryFn: conversationsApi.list,
  });

  const search = useQuery({
    queryKey: ["conversations", "search", query],
    queryFn: () => conversationsApi.search(query.trim()),
    enabled: query.trim().length > 0,
  });

  const detail = useQuery({
    queryKey: ["conversations", "detail", selected],
    queryFn: () => conversationsApi.get(selected!),
    enabled: selected !== null,
  });

  const conversations = list.data?.conversations ?? [];
  const searchHits = search.data?.sessions ?? [];
  const showingSearch = query.trim().length > 0;

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Sessions</h2>
        <p className="text-text-dim text-sm">
          Conversation history. Click a session to view the message timeline.
        </p>
      </div>

      <div className="mb-3">
        <input
          type="search"
          placeholder="search across all sessions…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="w-full bg-surface border border-border rounded px-3 py-1.5 text-sm"
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[2fr_3fr] gap-4">
        {/* Left column — session list */}
        <div className="border border-border rounded bg-surface max-h-[70vh] overflow-y-auto">
          {list.isLoading && <div className="p-4 text-text-dim text-sm">Loading…</div>}
          {showingSearch && searchHits.length === 0 && !search.isLoading && (
            <div className="p-4 text-text-dim text-sm">No matches.</div>
          )}
          {!showingSearch && conversations.length === 0 && !list.isLoading && (
            <div className="p-4 text-text-dim text-sm">No conversations yet.</div>
          )}
          <ul className="divide-y divide-border">
            {(showingSearch ? searchHits : conversations).map((row) => {
              const id = String((row as { sessionId: string }).sessionId);
              const title = (row as { title?: string }).title;
              const matchCount = (row as { matchCount?: number }).matchCount;
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
                    <div className="text-[10px] text-text-dim font-mono mt-0.5 flex items-center gap-2">
                      <span>{id.slice(0, 8)}</span>
                      {matchCount !== undefined && (
                        <span className="px-1 py-0.5 bg-accent/20 text-accent rounded">
                          {matchCount} matches
                        </span>
                      )}
                    </div>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>

        {/* Right column — selected detail */}
        <div className="border border-border rounded bg-surface max-h-[70vh] overflow-y-auto">
          {!selected && (
            <div className="p-6 text-text-dim text-sm text-center">
              Select a session on the left to view its messages.
            </div>
          )}
          {selected && detail.isLoading && (
            <div className="p-4 text-text-dim text-sm">Loading messages…</div>
          )}
          {selected && detail.data && (
            <div>
              <div className="border-b border-border p-3 flex items-baseline justify-between">
                <div>
                  <div className="text-sm font-semibold">
                    {detail.data.title || "Untitled"}
                  </div>
                  <div className="text-[10px] text-text-dim font-mono mt-0.5">
                    {selected}
                  </div>
                </div>
                <div className="flex gap-2 text-xs">
                  <a
                    href={`/api/conversations/${encodeURIComponent(
                      selected,
                    )}/export?format=markdown`}
                    className="px-2 py-1 border border-border rounded text-text-dim hover:text-text"
                  >
                    export md
                  </a>
                  <span className="text-text-dim self-center">
                    {detail.data.messageCount} msgs
                  </span>
                </div>
              </div>
              <div className="divide-y divide-border">
                {detail.data.messages.map((m, i) => (
                  <MessageRow key={i} message={m} />
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function MessageRow({
  message,
}: {
  message: {
    type?: string;
    role?: string;
    content?: unknown;
    timestamp?: string;
    [k: string]: unknown;
  };
}) {
  const role = message.role || message.type || "?";
  const ts = message.timestamp ? new Date(String(message.timestamp)).toLocaleString() : "";
  const content =
    typeof message.content === "string"
      ? message.content
      : JSON.stringify(message.content, null, 2);
  const tone =
    role === "user"
      ? "text-accent"
      : role === "assistant"
      ? "text-success"
      : "text-text-dim";

  return (
    <div className="p-3">
      <div className="flex items-baseline justify-between mb-1">
        <span className={`text-[10px] uppercase tracking-wider font-semibold ${tone}`}>
          {role}
        </span>
        {ts && <span className="text-[10px] text-text-dim font-mono">{ts}</span>}
      </div>
      <pre className="text-xs whitespace-pre-wrap break-words text-text/90 font-sans leading-relaxed">
        {content}
      </pre>
    </div>
  );
}
