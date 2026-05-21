import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { systemPromptApi } from "../api/console";

/**
 * View + edit the assistant's custom-instructions.md (the static prefix that
 * gets prepended to every system prompt). Token estimate is a deliberate
 * approximation — ~4 chars per token — to give a sense without pulling in a
 * tokenizer library on the browser.
 */
export function SystemPromptPage() {
  const [content, setContent] = useState("");
  const [loaded, setLoaded] = useState(false);
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({
    queryKey: ["system-prompt"],
    queryFn: systemPromptApi.get,
  });

  useEffect(() => {
    if (data && !loaded) {
      setContent(data.content ?? "");
      setLoaded(true);
    }
  }, [data, loaded]);

  const save = useMutation({
    mutationFn: () => systemPromptApi.put(content),
    onSuccess: (r) => {
      if (r.error) toast.error(`Save failed: ${r.error}`);
      else {
        toast.success("Saved (effective after restart)");
        qc.invalidateQueries({ queryKey: ["system-prompt"] });
      }
    },
    onError: (e: Error) => toast.error(`Save failed: ${e.message}`),
  });

  const chars = content.length;
  const lines = content.split("\n").length;
  const tokensApprox = Math.ceil(chars / 4);
  const dirty = data && content !== (data.content ?? "");

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="flex items-baseline justify-between mb-4">
        <div>
          <h2 className="text-lg font-semibold">System prompt</h2>
          <p className="text-text-dim text-sm">
            Custom instructions prepended to every agent system prompt. Changes
            take effect after server restart.
          </p>
        </div>
        <div className="text-xs text-text-dim font-mono">
          {chars.toLocaleString()} chars · {lines} lines · ~{tokensApprox.toLocaleString()} tokens
        </div>
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load: {(error as Error).message}
        </div>
      )}

      {!isLoading && (
        <>
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            rows={28}
            className="w-full bg-surface border border-border rounded p-3 text-sm font-mono"
            placeholder="Enter custom instructions in markdown…"
          />
          <div className="flex items-center gap-3 mt-3 text-xs">
            {data?.path && (
              <code className="text-text-dim font-mono truncate">{data.path}</code>
            )}
            <div className="ml-auto flex gap-2">
              <button
                type="button"
                disabled={!dirty}
                onClick={() => setContent(data?.content ?? "")}
                className="px-3 py-1.5 border border-border rounded hover:bg-primary/40 disabled:opacity-50"
              >
                Reset
              </button>
              <button
                type="button"
                disabled={!dirty || save.isPending}
                onClick={() => save.mutate()}
                className="px-3 py-1.5 bg-accent text-text rounded hover:bg-accent-hover disabled:opacity-50"
              >
                {save.isPending ? "Saving…" : "Save"}
              </button>
            </div>
          </div>
          {data?.note && (
            <div className="text-[10px] text-warn mt-3">{data.note}</div>
          )}
        </>
      )}
    </div>
  );
}
