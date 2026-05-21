import type { ComponentType } from "react";
import type { TranslationKey } from "../../i18n";
import { TaskListPage } from "../../pages/TaskListPage";
import { TaskCreatePage } from "../../pages/TaskCreatePage";
import { EvolutionPage } from "../../pages/EvolutionPage";
import { DashboardPage } from "../../pages/DashboardPage";
import { PluginsPage } from "../../pages/PluginsPage";
import { MemoryPage } from "../../pages/MemoryPage";
import { SessionsPage } from "../../pages/SessionsPage";
import { HealthPage } from "../../pages/HealthPage";
import { AnalyticsPage } from "../../pages/AnalyticsPage";
import { SkillsPage } from "../../pages/SkillsPage";
import { ToolsPage } from "../../pages/ToolsPage";
import { ChannelsPage } from "../../pages/ChannelsPage";
import { ChatPage } from "../../pages/ChatPage";
import { SystemPage } from "../../pages/SystemPage";
import { ToolHistoryPage } from "../../pages/ToolHistoryPage";
import { ReplayPage } from "../../pages/ReplayPage";

/**
 * Central registry of console tabs. Adding a tab is one line: register here +
 * write the component. The ConsoleShell renders the nav by iterating this
 * array; the router renders the routes the same way. Keyboard shortcuts are
 * derived from the index of the first 9 entries (1-9 keys) plus the optional
 * `digit` override.
 */
export interface ConsoleTab {
  id: string;
  labelKey: TranslationKey;
  path: string; // React Router path under BrowserRouter basename="/cron"
  component: ComponentType;
  /** Override the digit shortcut. Default: position (1-based) up to 9. */
  digit?: string;
  /** Hide from the top nav (route still exists). */
  hidden?: boolean;
}

export const TABS: ConsoleTab[] = [
  {
    id: "dashboard",
    labelKey: "tab.dashboard",
    path: "/dashboard",
    component: DashboardPage,
  },
  {
    id: "chat",
    labelKey: "tab.chat",
    path: "/chat",
    component: ChatPage,
  },
  {
    id: "tasks",
    labelKey: "tab.tasks",
    path: "/",
    component: TaskListPage,
  },
  {
    id: "create",
    labelKey: "tab.create",
    path: "/create",
    component: TaskCreatePage,
  },
  {
    id: "evolution",
    labelKey: "tab.evolution",
    path: "/evolution",
    component: EvolutionPage,
  },
  {
    id: "sessions",
    labelKey: "tab.sessions",
    path: "/sessions",
    component: SessionsPage,
  },
  {
    id: "replay",
    labelKey: "tab.replay",
    path: "/replay",
    component: ReplayPage,
  },
  {
    id: "memory",
    labelKey: "tab.memory",
    path: "/memory",
    component: MemoryPage,
  },
  {
    id: "skills",
    labelKey: "tab.skills",
    path: "/skills",
    component: SkillsPage,
  },
  {
    id: "tools",
    labelKey: "tab.tools",
    path: "/tools",
    component: ToolsPage,
  },
  {
    id: "tool-history",
    labelKey: "tab.toolHistory",
    path: "/tool-history",
    component: ToolHistoryPage,
  },
  {
    id: "plugins",
    labelKey: "tab.plugins",
    path: "/plugins",
    component: PluginsPage,
  },
  {
    id: "channels",
    labelKey: "tab.channels",
    path: "/channels",
    component: ChannelsPage,
  },
  {
    id: "analytics",
    labelKey: "tab.analytics",
    path: "/analytics",
    component: AnalyticsPage,
  },
  {
    id: "health",
    labelKey: "tab.health",
    path: "/health",
    component: HealthPage,
  },
  {
    id: "system",
    labelKey: "tab.system",
    path: "/system",
    component: SystemPage,
  },
];

/** Map a digit key (1-9) → tab id, or null. Computed once at module load. */
export function tabForDigit(digit: string): ConsoleTab | undefined {
  const visible = TABS.filter((t) => !t.hidden);
  const explicit = visible.find((t) => t.digit === digit);
  if (explicit) return explicit;
  const idx = parseInt(digit, 10) - 1;
  if (!Number.isNaN(idx) && idx >= 0 && idx < Math.min(9, visible.length)) {
    return visible[idx];
  }
  return undefined;
}
