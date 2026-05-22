import { useMemo, useState } from "react";
import type { CuratorRunResponse, EvolvedSkillView } from "../api/evolution";
import {
  useArchiveSkill,
  useEvolutionSkills,
  usePinSkill,
  useRunCurator,
  useRunLifecycle,
  useUnpinSkill,
} from "../hooks/useEvolution";

export function EvolutionPage() {
  const { data, isLoading, error } = useEvolutionSkills();
  const pin = usePinSkill();
  const unpin = useUnpinSkill();
  const archive = useArchiveSkill();
  const runCurator = useRunCurator();
  const runLifecycle = useRunLifecycle();

  const [filter, setFilter] = useState<"all" | "active" | "stale" | "archived">("all");
  const [lastReport, setLastReport] = useState<CuratorRunResponse | null>(null);

  const skills = useMemo(() => {
    const all = data?.skills ?? [];
    if (filter === "all") return all;
    return all.filter((s) => (s.state ?? "ACTIVE").toLowerCase() === filter);
  }, [data, filter]);

  return (
    <div className="p-6 max-w-6xl mx-auto">
      <div className="flex items-baseline justify-between mb-6">
        <div>
          <h2 className="text-lg font-semibold">Evolved skills</h2>
          <p className="text-text-dim text-sm">
            Lifecycle states + curator-driven umbrella consolidation. Mirrors the Hermes curator
            model.
          </p>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            disabled={runLifecycle.isPending}
            onClick={() => runLifecycle.mutateAsync()}
            className="px-3 py-1.5 rounded text-sm bg-surface border border-border hover:bg-primary/40 disabled:opacity-50"
          >
            {runLifecycle.isPending ? "Aging…" : "Run lifecycle"}
          </button>
          <button
            type="button"
            disabled={runCurator.isPending}
            onClick={async () => {
              const r = await runCurator.mutateAsync(true);
              setLastReport(r);
            }}
            className="px-3 py-1.5 rounded text-sm bg-surface border border-border hover:bg-primary/40 disabled:opacity-50"
          >
            {runCurator.isPending ? "Planning…" : "Curator dry-run"}
          </button>
          <button
            type="button"
            disabled={runCurator.isPending}
            onClick={async () => {
              if (!confirm("Run curator live? This will modify the skill library.")) return;
              const r = await runCurator.mutateAsync(false);
              setLastReport(r);
            }}
            className="px-3 py-1.5 rounded text-sm bg-accent text-text hover:bg-accent-hover disabled:opacity-50"
          >
            {runCurator.isPending ? "Running…" : "Curator run (live)"}
          </button>
        </div>
      </div>

      <div className="flex gap-2 mb-4 text-xs">
        {(["all", "active", "stale", "archived"] as const).map((f) => (
          <button
            key={f}
            type="button"
            onClick={() => setFilter(f)}
            className={[
              "px-2 py-1 rounded border",
              filter === f
                ? "bg-primary text-text border-border"
                : "border-border text-text-dim hover:text-text",
            ].join(" ")}
          >
            {f}
            <span className="ml-1 opacity-60">
              {f === "all"
                ? (data?.skills ?? []).length
                : (data?.skills ?? []).filter((s) => (s.state ?? "ACTIVE").toLowerCase() === f).length}
            </span>
          </button>
        ))}
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load skills: {(error as Error).message}
        </div>
      )}

      {!isLoading && !error && (
        <div className="border border-border rounded">
          <table className="w-full text-sm">
            <thead className="bg-surface text-text-dim text-xs uppercase tracking-wider">
              <tr>
                <th className="text-left px-3 py-2">Name</th>
                <th className="text-left px-3 py-2">State</th>
                <th className="text-left px-3 py-2">Provenance</th>
                <th className="text-right px-3 py-2">Use</th>
                <th className="text-right px-3 py-2">View</th>
                <th className="text-right px-3 py-2">Patch</th>
                <th className="text-left px-3 py-2">Last activity</th>
                <th className="text-right px-3 py-2">Actions</th>
              </tr>
            </thead>
            <tbody>
              {skills.length === 0 && (
                <tr>
                  <td colSpan={8} className="text-center py-6 text-text-dim text-sm">
                    No skills match the current filter.
                  </td>
                </tr>
              )}
              {skills.map((s) => (
                <SkillRow
                  key={s.name}
                  skill={s}
                  onPin={() => pin.mutate(s.name)}
                  onUnpin={() => unpin.mutate(s.name)}
                  onArchive={() => {
                    if (confirm(`Archive '${s.name}'? Archive is reversible.`)) {
                      archive.mutate(s.name);
                    }
                  }}
                  busy={pin.isPending || unpin.isPending || archive.isPending}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {lastReport && (
        <div className="mt-6 border border-border rounded p-4 bg-surface">
          <div className="flex justify-between items-baseline mb-2">
            <h3 className="text-sm font-semibold">
              Curator {lastReport.dryRun ? "dry-run" : "live"} report
            </h3>
            <button
              type="button"
              onClick={() => setLastReport(null)}
              className="text-xs text-text-dim hover:text-text"
            >
              dismiss
            </button>
          </div>
          <div className="text-xs text-text-dim mb-3">
            {lastReport.totalChanged} change(s) · applied={lastReport.consolidation.applied.length} ·
            skipped={lastReport.consolidation.skipped.length}
          </div>
          <ReportSteps title="Applied" steps={lastReport.consolidation.applied} />
          <ReportSteps title="Skipped" steps={lastReport.consolidation.skipped} />
        </div>
      )}
    </div>
  );
}

function SkillRow({
  skill,
  onPin,
  onUnpin,
  onArchive,
  busy,
}: {
  skill: EvolvedSkillView;
  onPin: () => void;
  onUnpin: () => void;
  onArchive: () => void;
  busy: boolean;
}) {
  const state = skill.state ?? "ACTIVE";
  const lastActivity =
    skill.lastUsedAt || skill.lastViewedAt || skill.lastPatchedAt || skill.updatedAt;
  return (
    <tr className="border-t border-border hover:bg-primary/10">
      <td className="px-3 py-2 font-mono text-xs">
        <div>{skill.name}</div>
        {skill.absorbedInto && (
          <div className="text-text-dim text-[10px]">→ {skill.absorbedInto}</div>
        )}
      </td>
      <td className="px-3 py-2">
        <StateBadge state={state} pinned={skill.pinned} />
      </td>
      <td className="px-3 py-2 text-xs text-text-dim">{skill.provenance ?? "—"}</td>
      <td className="px-3 py-2 text-right tabular-nums">{skill.useCount ?? 0}</td>
      <td className="px-3 py-2 text-right tabular-nums">{skill.viewCount ?? 0}</td>
      <td className="px-3 py-2 text-right tabular-nums">{skill.patchCount ?? 0}</td>
      <td className="px-3 py-2 text-xs text-text-dim">
        {lastActivity ? new Date(lastActivity).toLocaleString() : "—"}
      </td>
      <td className="px-3 py-2 text-right">
        <div className="inline-flex gap-1">
          {skill.pinned ? (
            <button
              type="button"
              disabled={busy}
              onClick={onUnpin}
              className="px-2 py-0.5 text-xs border border-border rounded hover:bg-primary/40 disabled:opacity-50"
            >
              unpin
            </button>
          ) : (
            <button
              type="button"
              disabled={busy}
              onClick={onPin}
              className="px-2 py-0.5 text-xs border border-border rounded hover:bg-primary/40 disabled:opacity-50"
            >
              pin
            </button>
          )}
          {state !== "ARCHIVED" && (
            <button
              type="button"
              disabled={busy}
              onClick={onArchive}
              className="px-2 py-0.5 text-xs border border-border rounded text-red-300 hover:bg-red-500/20 disabled:opacity-50"
            >
              archive
            </button>
          )}
        </div>
      </td>
    </tr>
  );
}

function StateBadge({ state, pinned }: { state: string; pinned?: boolean }) {
  const tone =
    state === "ARCHIVED"
      ? "bg-text-dim/20 text-text-dim"
      : state === "STALE"
      ? "bg-yellow-500/20 text-yellow-300"
      : "bg-green-500/20 text-green-300";
  return (
    <span className="inline-flex items-center gap-1">
      <span className={`text-[10px] px-1.5 py-0.5 rounded ${tone}`}>{state}</span>
      {pinned && (
        <span className="text-[10px] px-1 py-0.5 rounded bg-accent/20 text-accent">📌</span>
      )}
    </span>
  );
}

function ReportSteps({
  title,
  steps,
}: {
  title: string;
  steps: import("../api/evolution").ConsolidationStep[];
}) {
  if (steps.length === 0) return null;
  return (
    <div className="mb-3">
      <div className="text-xs font-semibold text-text-dim mb-1">
        {title} ({steps.length})
      </div>
      <ul className="space-y-1">
        {steps.map((s, i) => (
          <li key={i} className="text-xs font-mono border border-border rounded px-2 py-1">
            <span className="text-accent">{s.kind}</span>{" "}
            <span className="text-text-dim">on</span>{" "}
            <span>{s.umbrella}</span>
            {s.siblings && s.siblings.length > 0 && (
              <span className="text-text-dim"> ← {s.siblings.join(", ")}</span>
            )}
            {s.sibling && <span className="text-text-dim"> ← {s.sibling}</span>}
            <div className="text-text-dim ml-2 mt-0.5">{s.message}</div>
            {s.rationale && (
              <div className="text-text-dim/70 ml-2 italic">{s.rationale}</div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
