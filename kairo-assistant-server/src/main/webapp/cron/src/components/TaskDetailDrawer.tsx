import { useState } from "react";
import type { CronTaskView } from "../api/types";
import { useEditTask } from "../hooks/useCron";
import { StatusBadges } from "./StatusBadge";
import { RelativeTime } from "./RelativeTime";

export function TaskDetailDrawer({
  task,
  onClose,
}: {
  task: CronTaskView;
  onClose: () => void;
}) {
  const [cron, setCron] = useState(task.cron);
  const [prompt, setPrompt] = useState(task.prompt);
  const edit = useEditTask();
  const dirty = cron !== task.cron || prompt !== task.prompt;

  return (
    <div
      className="fixed inset-0 bg-black/40 backdrop-blur-sm z-40 flex justify-end"
      onClick={onClose}
    >
      <aside
        className="w-full max-w-2xl bg-surface border-l border-border h-full overflow-y-auto p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="flex items-start justify-between gap-4 mb-6">
          <div>
            <h2 className="text-lg font-semibold">
              Task <span className="font-mono text-text-dim">{task.id}</span>
            </h2>
            <div className="mt-2">
              <StatusBadges task={task} />
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-text-dim hover:text-text text-xl leading-none px-2"
            aria-label="Close"
          >
            ×
          </button>
        </header>

        <section className="space-y-4 text-sm">
          <Field label="Schedule (cron or interval)">
            <input
              value={cron}
              onChange={(e) => setCron(e.target.value)}
              className="w-full bg-bg border border-border rounded px-2 py-1 font-mono"
            />
          </Field>
          <Field label="Prompt">
            <textarea
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              rows={6}
              className="w-full bg-bg border border-border rounded px-2 py-1 font-mono"
            />
          </Field>

          <button
            disabled={!dirty || edit.isPending}
            onClick={() =>
              edit.mutate(
                {
                  id: task.id,
                  body: {
                    cron: cron === task.cron ? undefined : cron,
                    prompt: prompt === task.prompt ? undefined : prompt,
                  },
                },
                { onSuccess: () => onClose() },
              )
            }
            className="px-3 py-1.5 bg-accent text-text rounded text-sm hover:bg-accent-hover disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {edit.isPending ? "Saving…" : "Save changes"}
          </button>

          <hr className="border-border" />

          <ReadOnly label="Created" value={<RelativeTime iso={task.createdAt} />} />
          <ReadOnly label="Last fired" value={<RelativeTime iso={task.lastFiredAt} />} />
          <ReadOnly label="Next run" value={<RelativeTime iso={task.nextRunAt} />} />

          {task.skills && task.skills.length > 0 && (
            <ReadOnly
              label="Skills"
              value={
                <span className="font-mono text-xs">{task.skills.join(", ")}</span>
              }
            />
          )}
          {task.workdir && (
            <ReadOnly label="Workdir" value={<code>{task.workdir}</code>} />
          )}
          {task.noAgent && (
            <ReadOnly
              label="Script (no-agent)"
              value={
                <pre className="bg-bg border border-border rounded p-2 text-xs whitespace-pre-wrap font-mono">
                  {task.script ?? ""}
                </pre>
              }
            />
          )}
          {task.contextFromTaskId && (
            <ReadOnly
              label="Context from"
              value={<code>{task.contextFromTaskId}</code>}
            />
          )}
          {task.lastError && (
            <ReadOnly
              label="Last error"
              value={
                <span className="text-accent font-mono text-xs">
                  {task.lastError}
                </span>
              }
            />
          )}
        </section>
      </aside>
    </div>
  );
}

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-text-dim text-xs uppercase tracking-wider">
        {label}
      </span>
      <div className="mt-1">{children}</div>
    </label>
  );
}

function ReadOnly({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[120px_1fr] gap-3 items-baseline">
      <span className="text-text-dim text-xs uppercase tracking-wider">
        {label}
      </span>
      <div className="text-sm break-words">{value}</div>
    </div>
  );
}
