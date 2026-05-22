import { useState } from "react";
import type { CronTaskView } from "../api/types";
import {
  useEditTask,
  usePauseTask,
  useResumeTask,
  useTriggerTask,
  useDeleteTask,
} from "../hooks/useCron";
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
  const pause = usePauseTask();
  const resume = useResumeTask();
  const trigger = useTriggerTask();
  const del = useDeleteTask();
  const dirty = cron !== task.cron || prompt !== task.prompt;
  const failing = (task.consecutiveFailures ?? 0) > 0;

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

        {/* Status block — surfaces error + failures prominently at the top */}
        <section
          className={
            "mb-5 border rounded p-3 " +
            (failing
              ? "border-red-500/40 bg-red-500/10"
              : "border-border bg-bg/40")
          }
        >
          <div className="grid grid-cols-3 gap-3 mb-2 text-xs">
            <Metric label="Last fired" value={<RelativeTime iso={task.lastFiredAt} />} />
            <Metric label="Next run" value={<RelativeTime iso={task.nextRunAt} />} />
            <Metric
              label="Failures"
              value={
                <span className={failing ? "text-red-300 font-semibold" : ""}>
                  {task.consecutiveFailures ?? 0}
                </span>
              }
            />
          </div>
          {task.lastError && (
            <div className="mt-2">
              <div className="text-[10px] uppercase tracking-wider text-text-dim mb-1">
                Last error
              </div>
              <pre className="bg-bg border border-border rounded p-2 text-xs whitespace-pre-wrap font-mono text-red-200 max-h-48 overflow-y-auto">
                {task.lastError}
              </pre>
            </div>
          )}
          <div className="flex gap-2 mt-3 text-xs">
            <button
              type="button"
              disabled={trigger.isPending}
              onClick={() => trigger.mutate(task.id)}
              className="px-2 py-1 border border-border rounded hover:bg-primary/40 disabled:opacity-50"
            >
              {trigger.isPending ? "Triggering…" : "Trigger now"}
            </button>
            {task.paused ? (
              <button
                type="button"
                disabled={resume.isPending}
                onClick={() => resume.mutate(task.id)}
                className="px-2 py-1 border border-border rounded text-success hover:bg-success/20 disabled:opacity-50"
              >
                Resume
              </button>
            ) : (
              <button
                type="button"
                disabled={pause.isPending}
                onClick={() => pause.mutate(task.id)}
                className="px-2 py-1 border border-border rounded text-warn hover:bg-warn/20 disabled:opacity-50"
              >
                Pause
              </button>
            )}
            <button
              type="button"
              disabled={del.isPending}
              onClick={() => {
                if (confirm(`Delete task ${task.id}? This cannot be undone.`)) {
                  del.mutate(task.id, { onSuccess: () => onClose() });
                }
              }}
              className="ml-auto px-2 py-1 border border-border rounded text-red-300 hover:bg-red-500/20 disabled:opacity-50"
            >
              Delete
            </button>
          </div>
        </section>

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
        </section>
      </aside>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-text-dim text-xs uppercase tracking-wider">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}

function ReadOnly({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[120px_1fr] gap-3 items-baseline">
      <span className="text-text-dim text-xs uppercase tracking-wider">{label}</span>
      <div className="text-sm break-words">{value}</div>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <div className="text-[10px] uppercase tracking-wider text-text-dim">{label}</div>
      <div className="text-sm font-semibold mt-0.5">{value}</div>
    </div>
  );
}
