import { useEffect, useState } from "react";

const BOOT_KEY = "kc-booted";
const BOOT_LINES = [
  "[ ok ] initializing kairo console…",
  "[ ok ] event-stream channel: SSE",
  "[ ok ] theme: hermes / midnight / terminal",
  "[ ok ] i18n: en / zh",
  "[ ok ] shortcuts: 1-9 · t · l · ? · Cmd+K",
  "[ ok ] ready.",
];

/**
 * Brief animated splash shown once per browser session. Dismissable, and
 * remembered via sessionStorage so the boot screen doesn't re-trigger on
 * every tab navigation.
 */
export function BootScreen({ onDone }: { onDone: () => void }) {
  const [shown, setShown] = useState(0);

  useEffect(() => {
    if (shown >= BOOT_LINES.length) {
      const t = setTimeout(onDone, 400);
      return () => clearTimeout(t);
    }
    const t = setTimeout(() => setShown((s) => s + 1), 130);
    return () => clearTimeout(t);
  }, [shown, onDone]);

  return (
    <div className="fixed inset-0 z-[60] bg-bg flex items-center justify-center font-mono text-sm">
      <div className="text-left w-[420px] max-w-[90vw]">
        <div className="text-accent text-2xl mb-4 font-bold">☤ Kairo Console</div>
        {BOOT_LINES.slice(0, shown).map((line, i) => (
          <div
            key={i}
            className={
              "text-text-dim leading-relaxed " +
              (line.endsWith("ready.") ? "text-success" : "")
            }
          >
            {line}
          </div>
        ))}
        {shown < BOOT_LINES.length && (
          <span className="inline-block w-2 h-4 bg-accent animate-pulse mt-1" />
        )}
      </div>
    </div>
  );
}

export function useBootScreen() {
  const [open, setOpen] = useState(() => sessionStorage.getItem(BOOT_KEY) !== "true");
  const dismiss = () => {
    sessionStorage.setItem(BOOT_KEY, "true");
    setOpen(false);
  };
  return { open, dismiss };
}
