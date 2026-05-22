import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  memoryApi,
  type MemoryEntryView,
  type MemoryScope,
} from "../api/console";

const SCOPES: MemoryScope[] = ["GLOBAL", "USER", "AGENT", "SESSION", "TASK"];

export function MemoryPage() {
  const [scope, setScope] = useState<MemoryScope>("GLOBAL");
  const [query, setQuery] = useState("");
  const [showAdd, setShowAdd] = useState(false);
  const qc = useQueryClient();

  const listKey = ["memory", scope] as const;
  const searchKey = ["memory", scope, "search", query] as const;
  const activeKey = query.trim() ? searchKey : listKey;

  const { data, isLoading, error } = useQuery({
    queryKey: activeKey,
    queryFn: () =>
      query.trim() ? memoryApi.search(query.trim(), scope) : memoryApi.list(scope),
  });

  const save = useMutation({
    mutationFn: memoryApi.save,
    onSuccess: () => {
      toast.success("Memory saved");
      qc.invalidateQueries({ queryKey: ["memory"] });
      setShowAdd(false);
    },
    onError: (e: Error) => toast.error(`Save failed: ${e.message}`),
  });

  const del = useMutation({
    mutationFn: memoryApi.delete,
    onSuccess: () => {
      toast.success("Deleted");
      qc.invalidateQueries({ queryKey: ["memory"] });
    },
    onError: (e: Error) => toast.error(`Delete failed: ${e.message}`),
  });

  const entries = data?.entries ?? [];

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="flex items-baseline justify-between mb-4">
        <div>
          <h2 className="text-lg font-semibold">Memory</h2>
          <p className="text-text-dim text-sm">
            Persistent knowledge stored by the agent. Importance drives recall priority.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setShowAdd((v) => !v)}
          className="px-3 py-1.5 text-sm bg-accent text-text rounded hover:bg-accent-hover"
        >
          {showAdd ? "Cancel" : "+ Add entry"}
        </button>
      </div>

      <div className="flex gap-2 mb-4 text-xs flex-wrap">
        {SCOPES.map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => setScope(s)}
            className={[
              "px-2 py-1 rounded border",
              scope === s
                ? "bg-primary text-text border-border"
                : "border-border text-text-dim hover:text-text",
            ].join(" ")}
          >
            {s}
          </button>
        ))}
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="search…"
          className="ml-auto px-2 py-1 text-xs bg-surface border border-border rounded w-48"
        />
      </div>

      {showAdd && <AddForm scope={scope} onSubmit={save.mutate} pending={save.isPending} />}

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load memory: {(error as Error).message}
        </div>
      )}

      {!isLoading && !error && (
        <div className="border border-border rounded">
          {entries.length === 0 && (
            <div className="text-center py-6 text-text-dim text-sm">
              No entries in this scope{query ? ` matching "${query}"` : ""}.
            </div>
          )}
          <ul className="divide-y divide-border">
            {entries.map((entry) => (
              <EntryRow
                key={entry.id}
                entry={entry}
                onDelete={() => {
                  if (confirm(`Delete entry ${entry.id.slice(0, 8)}…?`)) {
                    del.mutate(entry.id);
                  }
                }}
              />
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function EntryRow({
  entry,
  onDelete,
}: {
  entry: MemoryEntryView;
  onDelete: () => void;
}) {
  return (
    <li className="p-3 hover:bg-primary/10 group">
      <div className="flex items-start gap-3">
        <ImportanceBar value={entry.importance} />
        <div className="flex-1 min-w-0">
          <div className="text-sm whitespace-pre-wrap break-words">{entry.content}</div>
          <div className="text-[10px] text-text-dim mt-1 flex flex-wrap gap-2">
            <span className="font-mono">{entry.id.slice(0, 8)}</span>
            {entry.timestamp && <span>{new Date(entry.timestamp).toLocaleString()}</span>}
            {entry.tags.map((tag) => (
              <span key={tag} className="px-1 py-0.5 bg-bg border border-border rounded">
                {tag}
              </span>
            ))}
          </div>
        </div>
        <button
          type="button"
          onClick={onDelete}
          className="opacity-0 group-hover:opacity-100 px-2 py-0.5 text-xs border border-border rounded text-red-300 hover:bg-red-500/20"
        >
          delete
        </button>
      </div>
    </li>
  );
}

function ImportanceBar({ value }: { value: number }) {
  const pct = Math.max(0, Math.min(1, value));
  return (
    <div className="flex flex-col items-center pt-1">
      <div className="w-1.5 h-12 bg-bg rounded relative overflow-hidden border border-border">
        <div
          className="absolute bottom-0 left-0 right-0 bg-accent"
          style={{ height: `${pct * 100}%` }}
        />
      </div>
      <span className="text-[9px] text-text-dim mt-1 tabular-nums">{(pct * 100).toFixed(0)}</span>
    </div>
  );
}

function AddForm({
  scope,
  onSubmit,
  pending,
}: {
  scope: MemoryScope;
  onSubmit: (body: { content: string; scope: MemoryScope; importance: number; tags: string[] }) => void;
  pending: boolean;
}) {
  const [content, setContent] = useState("");
  const [importance, setImportance] = useState(0.5);
  const [tagsInput, setTagsInput] = useState("");

  return (
    <form
      className="mb-4 border border-border rounded p-3 bg-surface space-y-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (!content.trim()) return;
        onSubmit({
          content: content.trim(),
          scope,
          importance,
          tags: tagsInput
            .split(",")
            .map((s) => s.trim())
            .filter(Boolean),
        });
        setContent("");
        setTagsInput("");
      }}
    >
      <textarea
        rows={3}
        value={content}
        onChange={(e) => setContent(e.target.value)}
        placeholder="Memory content…"
        className="w-full bg-bg border border-border rounded px-2 py-1 text-sm"
        required
      />
      <div className="flex items-center gap-3 text-xs">
        <label className="flex items-center gap-2">
          <span className="text-text-dim uppercase tracking-wider">Importance</span>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={importance}
            onChange={(e) => setImportance(parseFloat(e.target.value))}
            className="w-32"
          />
          <span className="tabular-nums w-8">{importance.toFixed(2)}</span>
        </label>
        <input
          value={tagsInput}
          onChange={(e) => setTagsInput(e.target.value)}
          placeholder="tags, comma-separated"
          className="ml-auto bg-bg border border-border rounded px-2 py-1 text-xs flex-1"
        />
        <button
          type="submit"
          disabled={pending}
          className="px-3 py-1 bg-accent text-text rounded text-xs hover:bg-accent-hover disabled:opacity-50"
        >
          {pending ? "Saving…" : "Save"}
        </button>
      </div>
    </form>
  );
}
