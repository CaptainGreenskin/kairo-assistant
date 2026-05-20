import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { tabForDigit } from "../lib/console/tabs";
import { useTheme } from "./useTheme";
import { useI18n } from "../i18n";

/**
 * Global keyboard navigation. Triggers:
 *   1-9  → switch to the corresponding tab (by index, 1-based)
 *   t    → cycle theme
 *   l    → toggle language
 *   ?    → open help overlay (caller-provided)
 * Ignored when an input/textarea has focus or a modifier key is held.
 */
export function useKeyboardNav(onShowHelp: () => void) {
  const navigate = useNavigate();
  const { cycleTheme } = useTheme();
  const { toggleLang } = useI18n();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (!target) return;
      const tag = target.tagName;
      if (
        tag === "INPUT" ||
        tag === "TEXTAREA" ||
        tag === "SELECT" ||
        target.isContentEditable
      ) {
        return;
      }
      if (e.metaKey || e.ctrlKey || e.altKey) return;

      // Digit → tab
      if (/^[1-9]$/.test(e.key)) {
        const tab = tabForDigit(e.key);
        if (tab) {
          navigate(tab.path);
          e.preventDefault();
        }
        return;
      }
      if (e.key === "t") {
        cycleTheme();
        e.preventDefault();
        return;
      }
      if (e.key === "l") {
        toggleLang();
        e.preventDefault();
        return;
      }
      if (e.key === "?") {
        onShowHelp();
        e.preventDefault();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [navigate, cycleTheme, toggleLang, onShowHelp]);
}
