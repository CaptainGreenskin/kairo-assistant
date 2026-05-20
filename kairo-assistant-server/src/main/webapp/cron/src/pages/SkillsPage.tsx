import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { skillsCatalogApi } from "../api/console";

export function SkillsPage() {
  const [filter, setFilter] = useState("");
  const { data, isLoading, error } = useQuery({
    queryKey: ["skills-catalog"],
    queryFn: skillsCatalogApi.list,
  });

  const skills = data?.skills ?? [];
  const f = filter.trim().toLowerCase();
  const filtered = f
    ? skills.filter((s) =>
        s.name.toLowerCase().includes(f) ||
        (s.description ?? "").toLowerCase().includes(f) ||
        (s.category ?? "").toLowerCase().includes(f),
      )
    : skills;

  const byCategory = filtered.reduce<Record<string, typeof skills>>((acc, s) => {
    const cat = s.category ?? "GENERAL";
    if (!acc[cat]) acc[cat] = [];
    acc[cat].push(s);
    return acc;
  }, {});

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="flex items-baseline justify-between mb-4">
        <div>
          <h2 className="text-lg font-semibold">Registered skills</h2>
          <p className="text-text-dim text-sm">
            Static skill catalog loaded into the SkillRegistry. Distinct from{" "}
            <strong>Evolution</strong>, which tracks agent-created skills with
            telemetry.
          </p>
        </div>
        <input
          type="search"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="filter…"
          className="px-2 py-1 text-xs bg-surface border border-border rounded w-48"
        />
      </div>

      {isLoading && <div className="text-text-dim text-sm">Loading…</div>}
      {error && (
        <div className="text-red-400 text-sm">
          Failed to load skills: {(error as Error).message}
        </div>
      )}

      {Object.entries(byCategory)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([cat, list]) => (
          <section key={cat} className="mb-6">
            <h3 className="text-xs uppercase tracking-wider text-text-dim mb-2">
              {cat}{" "}
              <span className="opacity-50">({list.length})</span>
            </h3>
            <div className="border border-border rounded divide-y divide-border">
              {list.map((s) => (
                <div key={s.name} className="p-3 hover:bg-primary/10">
                  <div className="flex items-baseline justify-between">
                    <h4 className="text-sm font-semibold">{s.name}</h4>
                    {s.version && (
                      <span className="text-[10px] text-text-dim font-mono">v{s.version}</span>
                    )}
                  </div>
                  {s.description && (
                    <p className="text-xs text-text-dim mt-1">{s.description}</p>
                  )}
                </div>
              ))}
            </div>
          </section>
        ))}
    </div>
  );
}
