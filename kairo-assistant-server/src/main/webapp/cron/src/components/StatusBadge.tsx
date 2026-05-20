import type { CronTaskView } from "../api/types";

export function StatusBadges({ task }: { task: CronTaskView }) {
  return (
    <span className="inline-flex flex-wrap gap-1.5">
      {task.paused ? (
        <Badge tone="warn">paused</Badge>
      ) : (
        <Badge tone="success">active</Badge>
      )}
      {task.recurring ? <Badge>recurring</Badge> : <Badge>one-shot</Badge>}
      {task.durable && <Badge tone="info">durable</Badge>}
      {task.noAgent && <Badge tone="info">no-agent</Badge>}
      {task.contextFromTaskId && (
        <Badge tone="info" title={`Chained from ${task.contextFromTaskId}`}>
          chained
        </Badge>
      )}
      {task.consecutiveFailures > 0 && (
        <Badge
          tone="error"
          title={task.lastError ?? "failures detected"}
        >
          {task.consecutiveFailures} failure(s)
        </Badge>
      )}
    </span>
  );
}

type Tone = "success" | "warn" | "error" | "info" | "neutral";

export function Badge({
  children,
  tone = "neutral",
  title,
}: {
  children: React.ReactNode;
  tone?: Tone;
  title?: string;
}) {
  const palette: Record<Tone, string> = {
    success: "bg-success/20 text-success border-success/30",
    warn: "bg-warn/20 text-warn border-warn/30",
    error: "bg-accent/20 text-accent border-accent/30",
    info: "bg-primary/40 text-text border-primary/60",
    neutral: "bg-border/30 text-text-dim border-border",
  };
  return (
    <span
      title={title}
      className={`inline-block px-1.5 py-0.5 text-[10px] uppercase tracking-wider border rounded ${palette[tone]}`}
    >
      {children}
    </span>
  );
}
