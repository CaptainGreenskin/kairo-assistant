import { useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { Menu, X } from "lucide-react";
import { CATEGORY_LABELS, TABS, tabsByCategory } from "../lib/console/tabs";
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

/**
 * Sidebar-based shell, inspired by hermes-agent/web. 21 tabs are grouped
 * into 4 categories (Run / History / Catalog / Operate) stacked vertically
 * on the left. Each tab has a lucide icon + label. On small screens the
 * sidebar collapses into a hamburger-driven drawer.
 *
 * Header (theme picker / lang toggle / help) sits in the topmost slot of
 * the sidebar; footer (status bar) is across the bottom. Keyboard shortcuts
 * (1-9, ⌘K, t, l, ?) still work — no change to useKeyboardNav.
 */
export function ConsoleShell({ children }: Props) {
  const { t, lang, toggleLang } = useI18n();
  const { theme, setTheme } = useTheme();
  const [helpOpen, setHelpOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const boot = useBootScreen();
  const loc = useLocation();
  const visible = TABS.filter((tab) => !tab.hidden);
  const grouped = tabsByCategory();
  const digitFor = (id: string) => {
    const idx = visible.findIndex((tab) => tab.id === id);
    return idx >= 0 && idx < 9 ? String(idx + 1) : null;
  };
  const activeTab = visible.find((tab) =>
    tab.path === "/" ? loc.pathname === "/" : loc.pathname.startsWith(tab.path),
  );

  useKeyboardNav(() => setHelpOpen(true));

  const closeDrawer = () => setDrawerOpen(false);

  const sidebar = (
    <aside
      className="bg-surface border-r border-border flex flex-col h-full w-60 shrink-0"
      style={{ minHeight: "100vh" }}
    >
      {/* Brand */}
      <Link
        to="/dashboard"
        onClick={closeDrawer}
        className="px-4 py-3 border-b border-border block"
      >
        <div className="text-base font-semibold tracking-tight">
          <span className="text-accent">☤ </span>
          {t("app.title")}
        </div>
        <div className="text-[10px] text-text-dim">{t("app.subtitle")}</div>
      </Link>

      {/* Nav */}
      <nav className="flex-1 overflow-y-auto py-2">
        {grouped.map(({ category, tabs }) => (
          <div key={category} className="mb-3">
            <div className="px-4 py-1 text-[10px] uppercase tracking-wider text-text-dim font-semibold">
              {t(CATEGORY_LABELS[category])}
            </div>
            {tabs.map((tab) => {
              const Icon = tab.icon;
              const active =
                tab.path === "/" ? loc.pathname === "/" : loc.pathname.startsWith(tab.path);
              const digit = digitFor(tab.id);
              return (
                <Link
                  key={tab.id}
                  to={tab.path}
                  onClick={closeDrawer}
                  className={[
                    "flex items-center gap-2.5 px-4 py-1.5 text-sm relative",
                    active
                      ? "bg-accent/15 text-accent border-r-2 border-accent"
                      : "text-text-dim hover:text-text hover:bg-primary/40",
                  ].join(" ")}
                  data-active={active}
                >
                  <Icon size={15} strokeWidth={1.75} />
                  <span className="flex-1 truncate">{t(tab.labelKey)}</span>
                  {digit && (
                    <kbd className="font-mono text-[10px] opacity-50">{digit}</kbd>
                  )}
                </Link>
              );
            })}
          </div>
        ))}
      </nav>

      {/* Sidebar footer — controls */}
      <div className="border-t border-border p-3 space-y-2">
        <div className="flex items-center justify-between gap-2">
          <ThemePicker theme={theme} setTheme={setTheme} />
          <div className="flex gap-1">
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
          </div>
        </div>
        <div className="text-[10px] text-text-dim flex items-center gap-2">
          <SseStatusBadge />
          <span className="opacity-60 font-mono">kairo-console</span>
        </div>
      </div>
    </aside>
  );

  return (
    <div className="min-h-full flex bg-bg text-text">
      {/* Desktop sidebar */}
      <div className="hidden md:flex sticky top-0 h-screen">{sidebar}</div>

      {/* Mobile drawer */}
      {drawerOpen && (
        <div
          className="md:hidden fixed inset-0 z-40 bg-black/50"
          onClick={closeDrawer}
        >
          <div onClick={(e) => e.stopPropagation()} className="h-full">
            {sidebar}
          </div>
        </div>
      )}

      <div className="flex-1 flex flex-col min-w-0">
        {/* Mobile header */}
        <header className="md:hidden bg-surface border-b border-border px-3 py-2 flex items-center gap-3 shrink-0">
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            className="p-1 text-text-dim hover:text-text"
            aria-label="Open menu"
          >
            <Menu size={20} />
          </button>
          <div className="text-sm font-semibold tracking-tight truncate">
            <span className="text-accent">☤ </span>
            {activeTab ? t(activeTab.labelKey) : t("app.title")}
          </div>
        </header>

        {/* Main */}
        <main className="flex-1 overflow-auto min-w-0">{children}</main>

        {/* Bottom shortcut hint (desktop only — sidebar shows status badge) */}
        <footer className="hidden md:flex bg-surface/60 border-t border-border px-4 py-1 text-[10px] text-text-dim items-center justify-between shrink-0">
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
          {activeTab && (
            <span className="opacity-50 font-mono">{activeTab.path}</span>
          )}
        </footer>
      </div>

      <ShortcutHelp open={helpOpen} onClose={() => setHelpOpen(false)} />
      <CommandPalette />
      {boot.open && <BootScreen onDone={boot.dismiss} />}

      {/* Mobile close button when drawer open — drawn last to overlay sidebar */}
      {drawerOpen && (
        <button
          type="button"
          onClick={closeDrawer}
          className="md:hidden fixed top-3 right-3 z-50 p-2 bg-surface border border-border rounded text-text-dim"
          aria-label="Close menu"
        >
          <X size={18} />
        </button>
      )}
    </div>
  );
}

function SseStatusBadge() {
  const status = useSseStatus();
  const { t } = useI18n();
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
