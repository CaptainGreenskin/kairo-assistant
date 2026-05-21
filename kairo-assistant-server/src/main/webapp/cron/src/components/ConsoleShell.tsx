import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { CATEGORY_LABELS, TABS, tabsByCategory, type ConsoleTab } from "../lib/console/tabs";
import { THEMES, useTheme } from "../hooks/useTheme";
import { useI18n } from "../i18n";
import { useKeyboardNav } from "../hooks/useKeyboardNav";
import { ShortcutHelp } from "./ShortcutHelp";
import { CommandPalette } from "./CommandPalette";
import { BootScreen, useBootScreen } from "./BootScreen";
import { useSseStatus } from "../hooks/useEventStream";

interface Props {
  children: React.ReactNode;
}

export function ConsoleShell({ children }: Props) {
  const { t, lang, toggleLang } = useI18n();
  const { theme, setTheme } = useTheme();
  const [helpOpen, setHelpOpen] = useState(false);
  const boot = useBootScreen();
  const loc = useLocation();
  const visible = TABS.filter((tab) => !tab.hidden);
  const grouped = tabsByCategory();
  const digitFor = (id: string) => {
    const idx = visible.findIndex((t) => t.id === id);
    return idx >= 0 && idx < 9 ? String(idx + 1) : null;
  };

  useKeyboardNav(() => setHelpOpen(true));

  const isActive = (path: string) => {
    if (path === "/") return loc.pathname === "/" || loc.pathname === "";
    return loc.pathname.startsWith(path);
  };

  return (
    <div className="min-h-full flex flex-col bg-bg text-text">
      {/* Top navigation */}
      <header className="bg-surface border-b border-border px-4 py-2 flex items-center gap-4 shrink-0">
        <Link to="/dashboard" className="text-base font-semibold tracking-tight whitespace-nowrap">
          <span className="text-accent">☤ </span>
          {t("app.title")}
          <span className="text-text-dim font-normal hidden md:inline"> · {t("app.subtitle")}</span>
        </Link>

        <nav className="flex gap-1 flex-1 min-w-0 text-sm">
          {grouped.map(({ category, tabs }) => (
            <CategoryDropdown
              key={category}
              label={t(CATEGORY_LABELS[category])}
              tabs={tabs}
              isActive={isActive}
              digitFor={digitFor}
              t={t}
            />
          ))}
        </nav>

        <div className="flex items-center gap-1 shrink-0">
          <ThemePicker theme={theme} setTheme={setTheme} />
          <button
            type="button"
            onClick={toggleLang}
            className="px-2 py-1 text-xs border border-border rounded text-text-dim hover:text-text"
            title={t("nav.lang")}
          >
            {lang.toUpperCase()}
          </button>
          <button
            type="button"
            onClick={() => setHelpOpen(true)}
            className="px-2 py-1 text-xs border border-border rounded text-text-dim hover:text-text font-mono"
            title={t("nav.help")}
          >
            ?
          </button>
          {/*
            Was a hard <a href="/"> back to the assistant home — but the
            assistant server has no SPA at "/", so it 404'd. The chat tab
            is the closest equivalent landing inside this SPA.
          */}
          <Link
            to="/chat"
            className="ml-2 text-text-dim hover:text-text text-xs underline-offset-2 hover:underline"
          >
            {t("app.backToAssistant")}
          </Link>
        </div>
      </header>

      {/* Main */}
      <main className="flex-1 overflow-auto">{children}</main>

      {/* Status bar */}
      <footer className="bg-surface border-t border-border px-4 py-1 text-xs text-text-dim flex items-center justify-between shrink-0">
        <span>
          <span className="opacity-50">{t("status.shortcuts")}:</span>{" "}
          <kbd className="font-mono mx-1">1-9</kbd> {t("status.tabs")}
          <span className="mx-2">·</span>
          <kbd className="font-mono mx-1">⌘K</kbd> palette
          <span className="mx-2">·</span>
          <kbd className="font-mono mx-1">t</kbd> {t("status.theme")}
          <span className="mx-2">·</span>
          <kbd className="font-mono mx-1">?</kbd> {t("nav.help")}
        </span>
        <span className="flex items-center gap-2 font-mono opacity-60">
          <SseStatusBadge />
          kairo-console
        </span>
      </footer>

      <ShortcutHelp open={helpOpen} onClose={() => setHelpOpen(false)} />
      <CommandPalette />
      {boot.open && <BootScreen onDone={boot.dismiss} />}
    </div>
  );
}

/**
 * Category bucket in the top nav. Click the header → opens a dropdown of the
 * tabs in that category. Header is highlighted when any of its tabs is active.
 * Dropdown closes on outside-click or Escape.
 */
function CategoryDropdown({
  label,
  tabs,
  isActive,
  digitFor,
  t,
}: {
  label: string;
  tabs: ConsoleTab[];
  isActive: (path: string) => boolean;
  digitFor: (id: string) => string | null;
  t: (key: import("../i18n").TranslationKey) => string;
}) {
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const anyActive = tabs.some((tab) => isActive(tab.path));

  useEffect(() => {
    if (!open) return;
    const click = (e: MouseEvent) => {
      if (!wrap.current?.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("mousedown", click);
    window.addEventListener("keydown", esc);
    return () => {
      window.removeEventListener("mousedown", click);
      window.removeEventListener("keydown", esc);
    };
  }, [open]);

  return (
    <div ref={wrap} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={[
          "px-3 py-1.5 rounded-md transition-colors text-sm",
          anyActive
            ? "bg-primary text-text"
            : "text-text-dim hover:text-text hover:bg-primary/40",
        ].join(" ")}
        aria-expanded={open}
      >
        {label}
        <span className="ml-1.5 text-xs opacity-50">▾</span>
      </button>
      {open && (
        <div className="absolute top-full left-0 mt-1 bg-surface border border-border rounded-md shadow-lg min-w-[180px] py-1 z-50">
          {tabs.map((tab) => {
            const active = isActive(tab.path);
            const digit = digitFor(tab.id);
            return (
              <Link
                key={tab.id}
                to={tab.path}
                onClick={() => setOpen(false)}
                className={[
                  "flex items-center justify-between px-3 py-1.5 text-sm whitespace-nowrap",
                  active
                    ? "bg-accent/20 text-accent"
                    : "text-text-dim hover:text-text hover:bg-primary/40",
                ].join(" ")}
              >
                <span>{t(tab.labelKey)}</span>
                {digit && (
                  <kbd className="ml-3 font-mono text-[10px] opacity-50">{digit}</kbd>
                )}
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}

function SseStatusBadge() {
  const status = useSseStatus();
  const { t } = useI18n();
  // Hide entirely until the EventSource has emitted its first state — avoids
  // a one-frame "connecting" flash every time the user navigates.
  if (status === null) return null;
  const tone =
    status === "live"
      ? "bg-success/20 text-success"
      : status === "connecting"
        ? "bg-warn/20 text-warn"
        : "bg-red-500/20 text-red-300";
  const label =
    status === "live"
      ? `● ${t("status.live")}`
      : status === "connecting"
        ? t("status.connecting")
        : t("status.offline");
  return <span className={`text-[10px] px-1.5 py-0.5 rounded ${tone}`}>{label}</span>;
}

function ThemePicker({
  theme,
  setTheme,
}: {
  theme: import("../hooks/useTheme").ThemeId;
  setTheme: (t: import("../hooks/useTheme").ThemeId) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="flex border border-border rounded overflow-hidden" title={t("nav.theme")}>
      {THEMES.map((th) => (
        <button
          key={th.id}
          type="button"
          onClick={() => setTheme(th.id)}
          className={[
            "px-2 py-1 text-xs",
            theme === th.id
              ? "bg-accent/20 text-accent"
              : "text-text-dim hover:text-text hover:bg-primary/40",
          ].join(" ")}
          title={th.label}
        >
          {th.icon}
        </button>
      ))}
    </div>
  );
}
