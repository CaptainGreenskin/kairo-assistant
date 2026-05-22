import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { TABS } from "../lib/console/tabs";
import { useI18n } from "../i18n";
import { useTheme, THEMES } from "../hooks/useTheme";

/**
 * Cmd+K / Ctrl+K command palette. Lists every tab + a handful of global
 * actions (cycle theme, toggle lang). Fuzzy-filter is intentionally
 * substring-only — no library. Arrow keys + Enter + Esc do the rest.
 */
export function CommandPalette() {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const { t, toggleLang, lang } = useI18n();
  const { cycleTheme, theme } = useTheme();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((v) => !v);
      } else if (e.key === "Escape" && open) {
        setOpen(false);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open]);

  useEffect(() => {
    if (open) {
      setQuery("");
      setActiveIndex(0);
      setTimeout(() => inputRef.current?.focus(), 10);
    }
  }, [open]);

  const commands = useMemo(() => {
    const tabCmds = TABS.filter((tab) => !tab.hidden).map((tab) => ({
      id: `tab:${tab.id}`,
      label: t(tab.labelKey),
      hint: `→ ${tab.path}`,
      action: () => navigate(tab.path),
    }));
    const themeCmd = {
      id: "act:theme",
      label: `${t("nav.theme")}: cycle (current ${THEMES.find((th) => th.id === theme)?.label})`,
      hint: "t",
      action: cycleTheme,
    };
    const langCmd = {
      id: "act:lang",
      label: `${t("nav.lang")}: toggle (${lang} → ${lang === "en" ? "zh" : "en"})`,
      hint: "l",
      action: toggleLang,
    };
    return [...tabCmds, themeCmd, langCmd];
  }, [navigate, t, theme, lang, cycleTheme, toggleLang]);

  const filtered = useMemo(() => {
    if (!query.trim()) return commands;
    const q = query.toLowerCase();
    return commands.filter((c) => c.label.toLowerCase().includes(q));
  }, [commands, query]);

  useEffect(() => {
    if (activeIndex >= filtered.length) setActiveIndex(0);
  }, [filtered.length, activeIndex]);

  if (!open) return null;

  const run = (idx: number) => {
    const cmd = filtered[idx];
    if (cmd) {
      cmd.action();
      setOpen(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center pt-32 bg-black/60"
      onClick={() => setOpen(false)}
    >
      <div
        className="bg-surface border border-border rounded-lg shadow-2xl w-full max-w-xl mx-4 overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <input
          ref={inputRef}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setActiveIndex(0);
          }}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown") {
              e.preventDefault();
              setActiveIndex((i) => Math.min(filtered.length - 1, i + 1));
            } else if (e.key === "ArrowUp") {
              e.preventDefault();
              setActiveIndex((i) => Math.max(0, i - 1));
            } else if (e.key === "Enter") {
              e.preventDefault();
              run(activeIndex);
            }
          }}
          placeholder="Type a command or page name…"
          className="w-full px-4 py-3 bg-surface border-b border-border outline-none text-sm"
        />
        <ul className="max-h-96 overflow-y-auto">
          {filtered.length === 0 && (
            <li className="px-4 py-3 text-text-dim text-sm">No matches.</li>
          )}
          {filtered.map((cmd, i) => (
            <li
              key={cmd.id}
              onMouseEnter={() => setActiveIndex(i)}
              onClick={() => run(i)}
              className={
                "px-4 py-2 cursor-pointer flex items-center justify-between " +
                (i === activeIndex ? "bg-primary text-text" : "text-text-dim hover:bg-primary/40")
              }
            >
              <span className="text-sm">{cmd.label}</span>
              <span className="text-xs font-mono opacity-50">{cmd.hint}</span>
            </li>
          ))}
        </ul>
        <div className="px-4 py-2 text-xs text-text-dim border-t border-border flex justify-between">
          <span>
            <kbd className="font-mono">↑↓</kbd> navigate ·{" "}
            <kbd className="font-mono">↵</kbd> select ·{" "}
            <kbd className="font-mono">Esc</kbd> close
          </span>
          <span className="opacity-50">Cmd+K</span>
        </div>
      </div>
    </div>
  );
}
