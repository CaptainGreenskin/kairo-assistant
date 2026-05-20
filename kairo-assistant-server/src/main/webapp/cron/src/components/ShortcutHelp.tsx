import { useEffect } from "react";
import { TABS } from "../lib/console/tabs";
import { useI18n } from "../i18n";

interface Props {
  open: boolean;
  onClose: () => void;
}

export function ShortcutHelp({ open, onClose }: Props) {
  const { t } = useI18n();

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, onClose]);

  if (!open) return null;

  const visible = TABS.filter((tab) => !tab.hidden).slice(0, 9);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
      onClick={onClose}
    >
      <div
        className="bg-surface border border-border rounded-lg shadow-xl p-6 max-w-md w-full mx-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-baseline justify-between mb-4">
          <h3 className="text-sm font-semibold uppercase tracking-wider">{t("help.title")}</h3>
          <button
            type="button"
            onClick={onClose}
            className="text-xs text-text-dim hover:text-text"
          >
            {t("help.close")}
          </button>
        </div>
        <ul className="space-y-2 text-sm">
          {visible.map((tab, i) => (
            <Row key={tab.id} keyLabel={String(i + 1)} description={`${t("help.tabPrefix")} · ${t(tab.labelKey)}`} />
          ))}
          <Row keyLabel="t" description={t("help.theme")} />
          <Row keyLabel="l" description={t("help.lang")} />
          <Row keyLabel="?" description={t("help.help")} />
        </ul>
      </div>
    </div>
  );
}

function Row({ keyLabel, description }: { keyLabel: string; description: string }) {
  return (
    <li className="flex items-center justify-between border-b border-border/50 pb-1">
      <span className="text-text-dim">{description}</span>
      <kbd className="font-mono text-xs px-2 py-0.5 border border-border rounded bg-bg">
        {keyLabel}
      </kbd>
    </li>
  );
}
