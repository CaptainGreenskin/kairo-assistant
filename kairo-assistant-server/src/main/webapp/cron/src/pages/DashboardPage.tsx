import { useEvolutionSkills } from "../hooks/useEvolution";
import { useCronTasks } from "../hooks/useCron";

/**
 * Executive overview tab. Surfaces a 1-screen snapshot of the assistant's
 * operational state by reusing the same React-Query data the other tabs
 * already fetch — so the page is free of extra REST calls.
 *
 * Phase-1 scope: counts only. Subsequent passes will add health pulse,
 * recent activity timeline, token-cost summary, etc., mirroring
 * hermes-hudui's executive dashboard.
 */
export function DashboardPage() {
  const { data: cronData } = useCronTasks();
  const { data: skillsData } = useEvolutionSkills();
  const skills = skillsData?.skills ?? [];

  const totalTasks = cronData?.total ?? 0;
  const tasks = cronData?.tasks ?? [];
  const pausedTasks = tasks.filter((t) => t.paused).length;
  const failingTasks = tasks.filter((t) => (t.consecutiveFailures ?? 0) > 0).length;

  const totalSkills = skills.length;
  const stale = skills.filter((s) => s.state === "STALE").length;
  const archived = skills.filter((s) => s.state === "ARCHIVED").length;
  const pinned = skills.filter((s) => s.pinned).length;

  return (
    <div className="p-6 max-w-6xl mx-auto">
      <div className="mb-6">
        <h2 className="text-lg font-semibold">At a glance</h2>
        <p className="text-text-dim text-sm">
          Live operational snapshot. Updates push over SSE — no manual refresh needed.
        </p>
      </div>

      <section className="mb-8">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Cron</h3>
        <div className="grid grid-cols-3 gap-3">
          <StatCard label="Tasks" value={totalTasks} />
          <StatCard label="Paused" value={pausedTasks} tone={pausedTasks > 0 ? "warn" : undefined} />
          <StatCard
            label="Failing"
            value={failingTasks}
            tone={failingTasks > 0 ? "error" : undefined}
          />
        </div>
      </section>

      <section className="mb-8">
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Skill library</h3>
        <div className="grid grid-cols-4 gap-3">
          <StatCard label="Skills" value={totalSkills} />
          <StatCard label="Pinned" value={pinned} />
          <StatCard label="Stale" value={stale} tone={stale > 0 ? "warn" : undefined} />
          <StatCard label="Archived" value={archived} />
        </div>
      </section>

      <section>
        <h3 className="text-xs uppercase tracking-wider text-text-dim mb-3">Next steps</h3>
        <ul className="text-sm text-text-dim space-y-1 list-disc list-inside">
          <li>More widgets here in the next iteration (health pulse, costs, model analytics).</li>
          <li>Press <kbd className="font-mono bg-bg px-1 border border-border rounded">?</kbd> for keyboard shortcuts.</li>
        </ul>
      </section>
    </div>
  );
}

function StatCard({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone?: "warn" | "error";
}) {
  const valueColor =
    tone === "error" ? "text-red-400" : tone === "warn" ? "text-yellow-300" : "text-text";
  return (
    <div className="border border-border rounded p-4 bg-surface">
      <div className="text-xs uppercase tracking-wider text-text-dim mb-1">{label}</div>
      <div className={`text-2xl font-semibold tabular-nums ${valueColor}`}>{value}</div>
    </div>
  );
}
