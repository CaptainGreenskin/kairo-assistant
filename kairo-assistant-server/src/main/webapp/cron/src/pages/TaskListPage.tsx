import { useMemo, useState } from "react";
import {
  useCronTasks,
  useDeleteTask,
  usePauseTask,
  useResumeTask,
  useTriggerTask,
} from "../hooks/useCron";
import type { CronTaskView } from "../api/types";
import { StatusBadges } from "../components/StatusBadge";
import { RelativeTime } from "../components/RelativeTime";
import { TaskDetailDrawer } from "../components/TaskDetailDrawer";

type Filter = "all" | "active" | "paused" | "durable" | "session";

export function TaskListPage() {
  const { data, isLoading, error } = useCronTasks();
  const [filter, setFilter] = useState<Filter>("all");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<CronTaskView | null>(null);

  const tasks = useMemo(() => {
    const all = data?.tasks ?? [];
    const q = search.trim().toLowerCase();
    return all
      .filter((t) => {
        switch (filter) {
          case "active":
            return !t.paused;
          case "paused":
            return t.paused;
          case "durable":
            return t.durable;
          case "session":
            return !t.durable;
          default:
            return true;
        }
      })
      .filter((t) =>
        q.length === 0
          ? true
          : t.id.toLowerCase().includes(q) ||
            t.cron.toLowerCase().includes(q) ||
            t.prompt.toLowerCase().includes(q),
      );
  }, [data?.tasks, filter, search]);

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <div className="flex flex-wrap items-center gap-3 mb-4">
        <FilterTabs current={filter} onChange={setFilter} />
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search id / cron / prompt…"
          className="bg-surface border border-border rounded px-3 py-1.5 text-sm flex-1 max-w-md"
        />
        <span className="text-text-dim text-xs">
          {data ? `${data.total} task(s)` : ""}
        </span>
      </div>

      {error && (
        <div className="bg-accent/10 border border-accent/30 text-accent rounded p-3 mb-4 text-sm">
          Failed to load cron tasks: {(error as Error).message}
        </div>
      )}

      <div className="bg-surface border border-border rounded overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-primary/40 text-text-dim text-xs uppercase tracking-wider">
            <tr>
              <th className="text-left px-3 py-2">id</th>
              <th className="text-left px-3 py-2">schedule</th>
              <th className="text-left px-3 py-2">prompt</th>
              <th className="text-left px-3 py-2">status</th>
              <th className="text-left px-3 py-2">last fired</th>
              <th className="text-left px-3 py-2">next run</th>
              <th className="text-right px-3 py-2">actions</th>
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr>
                <td colSpan={7} className="px-3 py-8 text-center text-text-dim">
                  Loading…
                </td>
              </tr>
            )}
            {!isLoading && tasks.length === 0 && (
              <tr>
                <td colSpan={7} className="px-3 py-8 text-center text-text-dim">
                  No cron tasks match the filter. Try{" "}
                  <a href="/cron/create" className="text-accent hover:underline">
                    creating one
                  </a>
                  .
                </td>
              </tr>
            )}
            {tasks.map((t) => (
              <Row key={t.id} task={t} onOpen={() => setSelected(t)} />
            ))}
          </tbody>
        </table>
      </div>

      {selected && (
        <TaskDetailDrawer
          task={selected}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}

function FilterTabs({
  current,
  onChange,
}: {
  current: Filter;
  onChange: (f: Filter) => void;
}) {
  const tabs: { id: Filter; label: string }[] = [
    { id: "all", label: "All" },
    { id: "active", label: "Active" },
    { id: "paused", label: "Paused" },
    { id: "durable", label: "Durable" },
    { id: "session", label: "Session" },
  ];
  return (
    <div className="flex border border-border rounded bg-surface overflow-hidden text-sm">
      {tabs.map((t) => (
        <button
          key={t.id}
          onClick={() => onChange(t.id)}
          className={[
            "px-3 py-1.5 transition-colors",
            current === t.id
              ? "bg-primary text-text"
              : "text-text-dim hover:text-text hover:bg-primary/40",
          ].join(" ")}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

function Row({
  task,
  onOpen,
}: {
  task: CronTaskView;
  onOpen: () => void;
}) {
  const pause = usePauseTask();
  const resume = useResumeTask();
  const trigger = useTriggerTask();
  const del = useDeleteTask();
  return (
    <tr className="border-t border-border hover:bg-primary/20">
      <td className="px-3 py-2 font-mono text-xs text-text-dim">{task.id}</td>
      <td className="px-3 py-2 font-mono text-xs">{task.cron}</td>
      <td className="px-3 py-2 max-w-xs truncate text-text" title={task.prompt}>
        {task.prompt}
      </td>
      <td className="px-3 py-2"><StatusBadges task={task} /></td>
      <td className="px-3 py-2 text-text-dim text-xs">
        <RelativeTime iso={task.lastFiredAt} />
      </td>
      <td className="px-3 py-2 text-text-dim text-xs">
        <RelativeTime iso={task.nextRunAt} />
      </td>
      <td className="px-3 py-2">
        <div className="flex justify-end gap-1.5">
          <ActionBtn onClick={onOpen}>Edit</ActionBtn>
          {task.paused ? (
            <ActionBtn onClick={() => resume.mutate(task.id)}>Resume</ActionBtn>
          ) : (
            <ActionBtn onClick={() => pause.mutate(task.id)}>Pause</ActionBtn>
          )}
          <ActionBtn onClick={() => trigger.mutate(task.id)}>Trigger</ActionBtn>
          <ActionBtn
            tone="danger"
            onClick={() => {
              if (
                window.confirm(
                  `Delete task ${task.id}? This can't be undone.`,
                )
              ) {
                del.mutate(task.id);
              }
            }}
          >
            Delete
          </ActionBtn>
        </div>
      </td>
    </tr>
  );
}

function ActionBtn({
  children,
  onClick,
  tone = "default",
}: {
  children: React.ReactNode;
  onClick: () => void;
  tone?: "default" | "danger";
}) {
  return (
    <button
      onClick={onClick}
      className={[
        "px-2 py-1 text-xs border rounded transition-colors",
        tone === "danger"
          ? "border-accent/40 text-accent hover:bg-accent/10"
          : "border-border text-text-dim hover:text-text hover:border-text-dim",
      ].join(" ")}
    >
      {children}
    </button>
  );
}
