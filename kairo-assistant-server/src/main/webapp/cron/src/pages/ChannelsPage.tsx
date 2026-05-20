import { useQuery } from "@tanstack/react-query";
import { channelsApi } from "../api/console";

export function ChannelsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["channels"],
    queryFn: channelsApi.list,
  });

  // Backend shape is loose — coerce to an array of objects.
  const channels: Array<Record<string, unknown>> = Array.isArray(data)
    ? (data as Array<Record<string, unknown>>)
    : data && typeof data === "object" && Array.isArray((data as { channels?: unknown[] }).channels)
    ? ((data as { channels: Array<Record<string, unknown>> }).channels)
    : [];

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">Channels</h2>
        <p className="text-text-dim text-sm">
          Configured external integrations (DingTalk, Feishu, webhooks). Each
          channel can receive cron task outputs and bot messages.
        </p>
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load channels: {(error as Error).message}
        </div>
      )}

      {!isLoading && channels.length === 0 && (
        <div className="text-center py-6 text-text-dim text-sm border border-border rounded">
          No channels configured. Configure DingTalk / Feishu credentials in
          environment variables to enable them.
        </div>
      )}

      {channels.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {channels.map((c, i) => (
            <ChannelCard key={String(c.id ?? c.name ?? i)} channel={c} />
          ))}
        </div>
      )}

      <details className="mt-6 border border-border rounded p-3 bg-surface">
        <summary className="cursor-pointer text-xs uppercase tracking-wider text-text-dim">
          Raw response
        </summary>
        <pre className="mt-2 text-xs text-text-dim font-mono overflow-x-auto whitespace-pre-wrap">
          {JSON.stringify(data, null, 2)}
        </pre>
      </details>
    </div>
  );
}

function ChannelCard({ channel }: { channel: Record<string, unknown> }) {
  const name = String(channel.name ?? channel.id ?? "(unnamed)");
  const type = channel.type ? String(channel.type) : null;
  const status = channel.status ? String(channel.status) : null;
  return (
    <div className="border border-border rounded p-4 bg-surface">
      <div className="flex items-baseline justify-between mb-1">
        <h3 className="font-semibold">{name}</h3>
        {status && (
          <span
            className={
              "text-[10px] px-1.5 py-0.5 rounded " +
              (status.toLowerCase() === "active" || status.toLowerCase() === "ok"
                ? "bg-green-500/20 text-green-300"
                : "bg-text-dim/20 text-text-dim")
            }
          >
            {status}
          </span>
        )}
      </div>
      {type && <div className="text-xs text-text-dim font-mono">{type}</div>}
    </div>
  );
}
