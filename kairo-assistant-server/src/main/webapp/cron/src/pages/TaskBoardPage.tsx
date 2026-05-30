import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  useCronTasks,
  usePauseTask,
  useResumeTask,
  useTriggerTask,
} from "../hooks/useCron";
import type { CronTaskView } from "../api/types";
import { TaskDetailDrawer } from "../components/TaskDetailDrawer";

/**
 * Kanban-style view of cron tasks. Columns are derived from task health:
 *   Active   — recurring & not paused & no failures
 *   One-shot — non-recurring (durable single-fire)
 *   Paused   — explicitly paused
 *   Failing  — consecutiveFailures > 0
 *
 * Columns are read-only (no drag-to-pause yet — that'd take extra plumbing
 * the existing list view already covers). Click any card to open the
 * shared TaskDetailDrawer for full edit + history + actions.
 */
type ColumnId = "active" | "oneshot" | "paused" | "failing";

const COLUMNS: { id: ColumnId; label: string; tone: string; description: string }[] = [
  {
    id: "failing",
    label: "Failing",
    tone: "border-red-500/40 bg-red-500/5",
    description: "consecutiveFailures > 0",
  },
  {
    id: "active",
    label: "Active",
    tone: "border-green-500/40 bg-green-500/5",
    description: "recurring, healthy, running",
  },
  {
    id: "oneshot",
    label: "One-shot",
    tone: "border-accent/40 bg-accent/5",
    description: "non-recurring (fire once + delete)",
  },
  {
    id: "paused",
    label: "Paused",
    tone: "border-yellow-500/40 bg-yellow-500/5",
    description: "explicitly paused",
  },
];

export function TaskBoardPage() {
  const { data, isLoading, error } = useCronTasks();
  const [selected, setSelected] = useState<CronTaskView | null>(null);

  const grouped = useMemo(() => {
    const out: Record<ColumnId, CronTaskView[]> = {
      active: [],
      oneshot: [],
      paused: [],
      failing: [],
    };
    const tasks = data?.items ?? [];
    for (const t of tasks) {
      if ((t.consecutiveFailures ?? 0) > 0) out.failing.push(t);
      else if (t.paused) out.paused.push(t);
      else if (!t.recurring) out.oneshot.push(t);
      else out.active.push(t);
    }
    return out;
  }, [data]);

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <div className="flex items-baseline justify-between mb-6">
        <div>
          <h2 className="text-lg font-semibold">Task board</h2>
          <p className="text-text-dim text-sm">
            Kanban view of cron tasks grouped by health. Click any card for full
            detail + actions, or jump to <Link to="/" className="text-accent underline">Tasks</Link> for the table.
          </p>
        </div>
        <div className="text-xs text-text-dim">{data?.total ?? 0} tasks total</div>
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load: {(error as Error).message}
        </div>
      )}

      {!isLoading && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
          {COLUMNS.map((col) => (
            <Column
              key={col.id}
              col={col}
              tasks={grouped[col.id]}
              onSelect={setSelected}
            />
          ))}
        </div>
      )}

      {selected && (
        <TaskDetailDrawer task={selected} onClose={() => setSelected(null)} />
      )}
    </div>
  );
}

function Column({
  col,
  tasks,
  onSelect,
}: {
  col: (typeof COLUMNS)[number];
  tasks: CronTaskView[];
  onSelect: (t: CronTaskView) => void;
}) {
  return (
    <div className={`border rounded ${col.tone} p-2 min-h-[120px]`}>
      <div className="flex items-baseline justify-between px-1 py-1 mb-2">
        <h3 className="text-xs uppercase tracking-wider font-semibold">
          {col.label} <span className="opacity-50">({tasks.length})</span>
        </h3>
      </div>
      <div className="text-[10px] text-text-dim px-1 mb-2 leading-snug">
        {col.description}
      </div>
      <ul className="space-y-2">
        {tasks.length === 0 && (
          <li className="text-text-dim text-xs italic px-1 py-2">empty</li>
        )}
        {tasks.map((t) => (
          <TaskCard key={t.id} task={t} onSelect={onSelect} />
        ))}
      </ul>
    </div>
  );
}

function TaskCard({
  task,
  onSelect,
}: {
  task: CronTaskView;
  onSelect: (t: CronTaskView) => void;
}) {
  const pause = usePauseTask();
  const resume = useResumeTask();
  const trigger = useTriggerTask();
  const failing = (task.consecutiveFailures ?? 0) > 0;

  return (
    <li
      onClick={() => onSelect(task)}
      className="bg-surface border border-border rounded p-2 hover:border-accent/60 cursor-pointer group"
    >
      <div className="flex items-start justify-between gap-1">
        <code className="text-[10px] text-text-dim font-mono shrink-0">
          {task.id.slice(0, 8)}
        </code>
        {failing && (
          <span className="text-[9px] px-1 py-0.5 rounded bg-red-500/20 text-red-300">
            ×{task.consecutiveFailures}
          </span>
        )}
      </div>
      <code className="block text-xs font-mono text-accent truncate mt-1" title={task.cron}>
        {task.cron}
      </code>
      <div className="text-xs text-text dim:text-text-dim line-clamp-2 mt-1 leading-snug">
        {task.prompt}
      </div>
      {task.lastError && (
        <div className="text-[10px] text-red-300 mt-1 font-mono truncate">
          {task.lastError}
        </div>
      )}
      <div
        className="flex gap-1 mt-2 opacity-0 group-hover:opacity-100 transition-opacity"
        onClick={(e) => e.stopPropagation()}
      >
        <button
          type="button"
          disabled={trigger.isPending}
          onClick={() => trigger.mutate(task.id)}
          className="text-[10px] px-1.5 py-0.5 border border-border rounded hover:bg-primary/40 disabled:opacity-50"
        >
          fire
        </button>
        {task.paused ? (
          <button
            type="button"
            disabled={resume.isPending}
            onClick={() => resume.mutate(task.id)}
            className="text-[10px] px-1.5 py-0.5 border border-border rounded text-success hover:bg-success/20 disabled:opacity-50"
          >
            resume
          </button>
        ) : (
          <button
            type="button"
            disabled={pause.isPending}
            onClick={() => pause.mutate(task.id)}
            className="text-[10px] px-1.5 py-0.5 border border-border rounded text-warn hover:bg-warn/20 disabled:opacity-50"
          >
            pause
          </button>
        )}
      </div>
    </li>
  );
}
