import { useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { TABS } from "../lib/console/tabs";
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

        <nav className="flex gap-0.5 flex-1 min-w-0 overflow-x-auto text-sm">
          {visible.map((tab, i) => {
            const active = isActive(tab.path);
            const digit = i < 9 ? String(i + 1) : null;
            return (
              <Link
                key={tab.id}
                to={tab.path}
                className={[
                  "px-3 py-1.5 rounded-md transition-colors whitespace-nowrap shrink-0",
                  active
                    ? "bg-primary text-text"
                    : "text-text-dim hover:text-text hover:bg-primary/40",
                ].join(" ")}
                data-active={active}
              >
                {digit && <span className="opacity-50 mr-1.5 text-xs">{digit}</span>}
                {t(tab.labelKey)}
              </Link>
            );
          })}
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
          <a
            href="/"
            className="ml-2 text-text-dim hover:text-text text-xs underline-offset-2 hover:underline"
          >
            {t("app.backToAssistant")}
          </a>
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

function SseStatusBadge() {
  const status = useSseStatus();
  const { t } = useI18n();
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
