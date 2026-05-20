import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useCreateTask, useCronTasks, useSkills } from "../hooks/useCron";

export function TaskCreatePage() {
  const navigate = useNavigate();
  const create = useCreateTask();
  const { data: skillsData } = useSkills();
  const { data: tasksData } = useCronTasks();

  const [cron, setCron] = useState("0 9 * * *");
  const [prompt, setPrompt] = useState("");
  const [recurring, setRecurring] = useState(true);
  const [durable, setDurable] = useState(false);
  const [noAgent, setNoAgent] = useState(false);
  const [script, setScript] = useState("");
  const [workdir, setWorkdir] = useState("");
  const [skills, setSelectedSkills] = useState<string[]>([]);
  const [contextFromTaskId, setContextFromTaskId] = useState("");

  const cronPreview = describeCron(cron);

  return (
    <div className="p-6 max-w-3xl mx-auto">
      <h2 className="text-lg font-semibold mb-1">Create scheduled task</h2>
      <p className="text-text-dim text-sm mb-6">
        Use a 5-field cron expression or interval syntax like{" "}
        <code className="font-mono">every 5m</code> /{" "}
        <code className="font-mono">every 1d at 09:00</code>.
      </p>

      <form
        className="space-y-5"
        onSubmit={(e) => {
          e.preventDefault();
          if (!cron.trim() || (!noAgent && !prompt.trim())) return;
          create.mutate(
            {
              cron: cron.trim(),
              prompt: prompt.trim() || "(no-agent script)",
              recurring,
              durable,
              skills: skills.length > 0 ? skills : undefined,
              workdir: workdir.trim() || undefined,
              noAgent,
              script: noAgent ? script : undefined,
              contextFromTaskId: contextFromTaskId || undefined,
            },
            { onSuccess: () => navigate("/") },
          );
        }}
      >
        <Field
          label="Schedule"
          hint={cronPreview ? `→ ${cronPreview}` : undefined}
        >
          <input
            value={cron}
            onChange={(e) => setCron(e.target.value)}
            className="w-full bg-surface border border-border rounded px-3 py-2 font-mono"
            placeholder="0 9 * * *  or  every 5m"
            required
          />
          <ChipRow
            chips={[
              "0 9 * * *",
              "*/15 * * * *",
              "0 */2 * * *",
              "every 5m",
              "every 1h",
              "every 1d at 09:00",
            ]}
            onPick={setCron}
          />
        </Field>

        <Field
          label={noAgent ? "Prompt (used as a label only in no-agent mode)" : "Prompt"}
        >
          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            rows={4}
            className="w-full bg-surface border border-border rounded px-3 py-2"
            placeholder={
              noAgent
                ? "e.g. nightly DB backup"
                : "What should the agent do when this fires?"
            }
            required={!noAgent}
          />
        </Field>

        <div className="flex flex-wrap gap-6">
          <Checkbox label="Recurring" checked={recurring} onChange={setRecurring} />
          <Checkbox label="Durable (survives restart)" checked={durable} onChange={setDurable} />
          <Checkbox
            label="No-agent mode (run as shell script)"
            checked={noAgent}
            onChange={setNoAgent}
          />
        </div>

        {noAgent && (
          <Field label="Shell script">
            <textarea
              value={script}
              onChange={(e) => setScript(e.target.value)}
              rows={6}
              className="w-full bg-surface border border-border rounded px-3 py-2 font-mono text-xs"
              placeholder={"#!/usr/bin/env bash\necho hi"}
              required
            />
          </Field>
        )}

        <Field
          label="Working directory (optional)"
          hint="Absolute path; agent tools resolve relative paths here"
        >
          <input
            value={workdir}
            onChange={(e) => setWorkdir(e.target.value)}
            className="w-full bg-surface border border-border rounded px-3 py-2 font-mono text-sm"
            placeholder="/Users/me/project"
          />
        </Field>

        <Field
          label="Skills to pre-load (optional)"
          hint="Each selected skill's instructions are prepended to the prompt before the agent runs."
        >
          <SkillPicker
            available={skillsData ?? []}
            selected={skills}
            onChange={setSelectedSkills}
          />
        </Field>

        <Field
          label="Chain from (optional)"
          hint="Output of the chosen task is prepended to this task's prompt before each firing."
        >
          <select
            value={contextFromTaskId}
            onChange={(e) => setContextFromTaskId(e.target.value)}
            className="w-full bg-surface border border-border rounded px-3 py-2 text-sm"
          >
            <option value="">— none —</option>
            {(tasksData?.tasks ?? []).map((t) => (
              <option key={t.id} value={t.id}>
                {t.id} — {t.cron} — {t.prompt.slice(0, 40)}
              </option>
            ))}
          </select>
        </Field>

        <Field
          label="Delivery (optional)"
          hint="Add @deliver:<target> directives to the prompt, e.g. @deliver:log or @deliver:file:/var/log/cron.txt or @deliver:https://hook.example/path"
        >
          <div className="text-xs text-text-dim">
            The prompt above is scanned for{" "}
            <code className="font-mono">@deliver:&lt;target&gt;</code> tokens at fire
            time and the agent's final response is routed to each.
          </div>
        </Field>

        <div className="flex items-center gap-3 pt-2">
          <button
            type="submit"
            disabled={create.isPending}
            className="px-4 py-2 bg-accent text-text rounded text-sm hover:bg-accent-hover disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {create.isPending ? "Creating…" : "Create task"}
          </button>
          <button
            type="button"
            onClick={() => navigate("/")}
            className="px-4 py-2 bg-surface border border-border rounded text-sm hover:bg-primary/40"
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-xs uppercase tracking-wider text-text-dim">
        {label}
      </span>
      <div className="mt-1">{children}</div>
      {hint && (
        <div className="text-xs text-text-dim mt-1">
          <span className="opacity-70">{hint}</span>
        </div>
      )}
    </label>
  );
}

function Checkbox({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className="flex items-center gap-2 text-sm cursor-pointer">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="w-4 h-4 accent-accent"
      />
      {label}
    </label>
  );
}

function ChipRow({
  chips,
  onPick,
}: {
  chips: string[];
  onPick: (v: string) => void;
}) {
  return (
    <div className="flex flex-wrap gap-1.5 mt-2">
      {chips.map((c) => (
        <button
          type="button"
          key={c}
          onClick={() => onPick(c)}
          className="px-2 py-0.5 text-xs font-mono border border-border rounded text-text-dim hover:text-text hover:border-text-dim"
        >
          {c}
        </button>
      ))}
    </div>
  );
}

function SkillPicker({
  available,
  selected,
  onChange,
}: {
  available: { name: string; description?: string }[];
  selected: string[];
  onChange: (v: string[]) => void;
}) {
  if (available.length === 0) {
    return (
      <input
        value={selected.join(",")}
        onChange={(e) =>
          onChange(
            e.target.value
              .split(",")
              .map((s) => s.trim())
              .filter(Boolean),
          )
        }
        placeholder="Comma-separated skill names"
        className="w-full bg-surface border border-border rounded px-3 py-2 text-sm font-mono"
      />
    );
  }
  return (
    <div className="flex flex-wrap gap-1.5 p-2 bg-surface border border-border rounded">
      {available.map((s) => {
        const on = selected.includes(s.name);
        return (
          <button
            type="button"
            key={s.name}
            title={s.description}
            onClick={() =>
              onChange(
                on
                  ? selected.filter((x) => x !== s.name)
                  : [...selected, s.name],
              )
            }
            className={[
              "px-2 py-1 text-xs border rounded transition-colors",
              on
                ? "bg-accent/20 text-accent border-accent/40"
                : "border-border text-text-dim hover:text-text hover:border-text-dim",
            ].join(" ")}
          >
            {s.name}
          </button>
        );
      })}
    </div>
  );
}

/** Very small cron humaniser — covers the most common shapes the form suggests. */
function describeCron(input: string): string {
  const s = input.trim();
  if (/^every\s+\d/i.test(s)) return s.toLowerCase();
  const parts = s.split(/\s+/);
  if (parts.length !== 5) return "";
  const [m, h, dom, mon, dow] = parts;
  if (m.startsWith("*/")) return `Every ${m.slice(2)} min`;
  if (h.startsWith("*/")) return `Every ${h.slice(2)} h on minute ${m}`;
  if (dom === "*" && mon === "*" && dow === "*")
    return `Daily at ${h.padStart(2, "0")}:${m.padStart(2, "0")}`;
  if (dow !== "*" && dom === "*" && mon === "*")
    return `Weekdays ${dow} at ${h.padStart(2, "0")}:${m.padStart(2, "0")}`;
  return `${s}`;
}
