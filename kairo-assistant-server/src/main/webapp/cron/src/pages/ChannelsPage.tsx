import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { channelsApi, type ChannelRecentMessage } from "../api/console";

export function ChannelsPage() {
  const [selected, setSelected] = useState<string | null>(null);
  const { data, isLoading, error } = useQuery({
    queryKey: ["channels"],
    queryFn: channelsApi.list,
  });

  const channels: Array<Record<string, unknown>> = Array.isArray(data)
    ? (data as Array<Record<string, unknown>>)
    : data &&
        typeof data === "object" &&
        Array.isArray((data as { channels?: unknown[] }).channels)
      ? ((data as { channels: Array<Record<string, unknown>> }).channels)
      : [];

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Channels</h2>
        <p className="text-text-dim text-sm">
          Configured external integrations (DingTalk, Feishu, webhooks). Click a
          channel to send a test message and inspect recent traffic.
        </p>
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load channels: {(error as Error).message}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_2fr] gap-4">
        {/* Left: channel list */}
        <div className="space-y-2">
          {channels.length === 0 && !isLoading && (
            <div className="text-center py-6 text-text-dim text-sm border border-border rounded">
              No channels available. Set channel-specific env vars to enable
              integrations.
            </div>
          )}
          {channels.map((c, i) => {
            const id = String(c.id ?? c.name ?? `ch-${i}`);
            const status = c.status ? String(c.status) : "—";
            const active = selected === id;
            return (
              <button
                key={id}
                type="button"
                onClick={() => setSelected(id)}
                className={
                  "w-full text-left border rounded p-3 transition-colors " +
                  (active
                    ? "border-accent/60 bg-accent/10"
                    : "border-border bg-surface hover:bg-primary/20")
                }
              >
                <div className="flex items-baseline justify-between mb-1">
                  <span className="font-semibold">{id}</span>
                  <StatusBadge status={status} />
                </div>
                {c.type !== undefined && c.type !== null && (
                  <div className="text-xs text-text-dim font-mono">{String(c.type)}</div>
                )}
              </button>
            );
          })}
        </div>

        {/* Right: detail */}
        <div>
          {!selected && (
            <div className="border border-border rounded bg-surface p-6 text-center text-text-dim text-sm">
              Select a channel to send test messages and view recent traffic.
            </div>
          )}
          {selected && (
            <>
              <SendTest channelId={selected} />
              <RecentMessages channelId={selected} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const tone =
    status.toLowerCase() === "active" || status.toLowerCase() === "ok"
      ? "bg-green-500/20 text-green-300"
      : status.toLowerCase() === "available"
        ? "bg-yellow-500/20 text-yellow-300"
        : "bg-text-dim/20 text-text-dim";
  return <span className={`text-[10px] px-1.5 py-0.5 rounded ${tone}`}>{status}</span>;
}

function SendTest({ channelId }: { channelId: string }) {
  const [destination, setDestination] = useState("default");
  const [content, setContent] = useState("Hello from Kairo Console");
  const qc = useQueryClient();

  const mutation = useMutation({
    mutationFn: () => channelsApi.send(channelId, destination, content),
    onSuccess: (r) => {
      if (r.error) {
        toast.error(`Channel error: ${r.error}`);
      } else {
        toast.success(`Sent (success=${r.sent})`);
      }
      qc.invalidateQueries({ queryKey: ["channels", channelId, "recent"] });
    },
    onError: (e: Error) => toast.error(`Send failed: ${e.message}`),
  });

  return (
    <div className="border border-border rounded bg-surface p-4 mb-4">
      <h3 className="text-sm font-semibold mb-3">Send test message</h3>
      <div className="space-y-2">
        <label className="block text-xs">
          <span className="text-text-dim uppercase tracking-wider">Destination</span>
          <input
            value={destination}
            onChange={(e) => setDestination(e.target.value)}
            className="mt-1 w-full bg-bg border border-border rounded px-2 py-1 text-sm font-mono"
            placeholder="default"
          />
        </label>
        <label className="block text-xs">
          <span className="text-text-dim uppercase tracking-wider">Content</span>
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            rows={3}
            className="mt-1 w-full bg-bg border border-border rounded px-2 py-1 text-sm"
          />
        </label>
        <div className="flex gap-2">
          <button
            type="button"
            disabled={mutation.isPending || !content.trim()}
            onClick={() => mutation.mutate()}
            className="px-3 py-1.5 bg-accent text-text rounded text-xs hover:bg-accent-hover disabled:opacity-50"
          >
            {mutation.isPending ? "Sending…" : "Send"}
          </button>
        </div>
      </div>
    </div>
  );
}

function RecentMessages({ channelId }: { channelId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ["channels", channelId, "recent"],
    queryFn: () => channelsApi.recent(channelId, 50),
    refetchInterval: 5_000,
  });
  const messages = data?.messages ?? [];

  return (
    <div className="border border-border rounded bg-surface">
      <div className="flex items-baseline justify-between border-b border-border p-3">
        <h3 className="text-sm font-semibold">Recent messages</h3>
        <span className="text-xs text-text-dim">
          {messages.length} / 50 · refreshes 5s
        </span>
      </div>
      {isLoading && <div className="p-4 text-text-dim text-sm">Loading…</div>}
      {!isLoading && messages.length === 0 && (
        <div className="p-6 text-text-dim text-sm text-center">
          No traffic yet. Send a test message above or wait for incoming webhooks.
        </div>
      )}
      <ul className="divide-y divide-border max-h-[60vh] overflow-y-auto">
        {messages.map((m, i) => (
          <MessageRow key={i} message={m} />
        ))}
      </ul>
    </div>
  );
}

function MessageRow({ message }: { message: ChannelRecentMessage }) {
  const direction =
    message.direction === "in"
      ? { label: "IN", tone: "bg-blue-500/20 text-blue-300" }
      : { label: "OUT", tone: "bg-purple-500/20 text-purple-300" };
  return (
    <li className="p-3 hover:bg-primary/10">
      <div className="flex items-baseline gap-2 mb-1">
        <span className={`text-[10px] px-1.5 py-0.5 rounded font-mono ${direction.tone}`}>
          {direction.label}
        </span>
        <span className="text-xs text-text-dim font-mono truncate">{message.destination}</span>
        {!message.success && (
          <span className="text-[10px] px-1.5 py-0.5 rounded bg-red-500/20 text-red-300">
            failed
          </span>
        )}
        <span className="ml-auto text-[10px] text-text-dim">
          {new Date(message.timestamp).toLocaleString()}
        </span>
      </div>
      <pre className="text-xs whitespace-pre-wrap break-words text-text/90 font-sans leading-relaxed">
        {message.content}
      </pre>
    </li>
  );
}
