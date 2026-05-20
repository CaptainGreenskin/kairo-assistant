import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

export type ThemeId = "hermes" | "midnight" | "terminal";

export const THEMES: { id: ThemeId; label: string; icon: string }[] = [
  { id: "hermes", label: "Hermes", icon: "☤" },
  { id: "midnight", label: "Midnight", icon: "◆" },
  { id: "terminal", label: "Terminal", icon: "▣" },
];

interface ThemeContextValue {
  theme: ThemeId;
  setTheme: (t: ThemeId) => void;
  cycleTheme: () => void;
}

const STORAGE_KEY = "kc-theme";

const ThemeContext = createContext<ThemeContextValue>({
  theme: "hermes",
  setTheme: () => {},
  cycleTheme: () => {},
});

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeId>(() => {
    const stored = localStorage.getItem(STORAGE_KEY);
    return THEMES.some((t) => t.id === stored) ? (stored as ThemeId) : "hermes";
  });

  const setTheme = (t: ThemeId) => {
    setThemeState(t);
    localStorage.setItem(STORAGE_KEY, t);
  };

  const cycleTheme = () => {
    const idx = THEMES.findIndex((t) => t.id === theme);
    setTheme(THEMES[(idx + 1) % THEMES.length].id);
  };

  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
  }, [theme]);

  return (
    <ThemeContext.Provider value={{ theme, setTheme, cycleTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

export const useTheme = () => useContext(ThemeContext);
