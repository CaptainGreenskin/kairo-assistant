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
import { TracePage } from "../../pages/TracePage";
import { ToolPlaygroundPage } from "../../pages/ToolPlaygroundPage";
import { SystemPromptPage } from "../../pages/SystemPromptPage";
import { ObservabilityPage } from "../../pages/ObservabilityPage";
import { TaskBoardPage } from "../../pages/TaskBoardPage";

/**
 * Central registry of console tabs. Adding a tab is one line: register here +
 * write the component. The ConsoleShell renders the nav by iterating this
 * array; the router renders the routes the same way. Keyboard shortcuts are
 * derived from the index of the first 9 entries (1-9 keys) plus the optional
 * `digit` override.
 */
/**
 * Top-nav grouping. Tabs are presented as four category buckets so the nav
 * doesn't overflow on narrow viewports; the order here is also the visual
 * order of the buckets in the top bar.
 */
export type TabCategory = "run" | "history" | "catalog" | "operate";

export const CATEGORY_LABELS: Record<TabCategory, TranslationKey> = {
  run: "cat.run",
  history: "cat.history",
  catalog: "cat.catalog",
  operate: "cat.operate",
};

export interface ConsoleTab {
  id: string;
  labelKey: TranslationKey;
  path: string; // React Router path under BrowserRouter basename="/cron"
  component: ComponentType;
  /** Override the digit shortcut. Default: position (1-based) up to 9. */
  digit?: string;
  /** Hide from the top nav (route still exists). */
  hidden?: boolean;
  /** Which top-nav bucket the tab lives in. Defaults to "run". */
  category?: TabCategory;
}

export const TABS: ConsoleTab[] = [
  // ----- Run -----
  { id: "dashboard", labelKey: "tab.dashboard", path: "/dashboard", component: DashboardPage, category: "run" },
  { id: "chat",      labelKey: "tab.chat",      path: "/chat",      component: ChatPage,      category: "run" },
  { id: "tasks",     labelKey: "tab.tasks",     path: "/",          component: TaskListPage,  category: "run" },
  { id: "board",     labelKey: "tab.board",     path: "/board",     component: TaskBoardPage, category: "run" },
  { id: "create",    labelKey: "tab.create",    path: "/create",    component: TaskCreatePage, category: "run" },

  // ----- History -----
  { id: "evolution", labelKey: "tab.evolution", path: "/evolution", component: EvolutionPage, category: "history" },
  { id: "sessions",  labelKey: "tab.sessions",  path: "/sessions",  component: SessionsPage,  category: "history" },
  { id: "replay",    labelKey: "tab.replay",    path: "/replay",    component: ReplayPage,    category: "history" },
  { id: "trace",     labelKey: "tab.trace",     path: "/trace",     component: TracePage,     category: "history" },
  { id: "memory",    labelKey: "tab.memory",    path: "/memory",    component: MemoryPage,    category: "history" },

  // ----- Catalog -----
  { id: "skills",          labelKey: "tab.skills",          path: "/skills",          component: SkillsPage,         category: "catalog" },
  { id: "tools",           labelKey: "tab.tools",           path: "/tools",           component: ToolsPage,          category: "catalog" },
  { id: "tool-history",    labelKey: "tab.toolHistory",     path: "/tool-history",    component: ToolHistoryPage,    category: "catalog" },
  { id: "tool-playground", labelKey: "tab.toolPlayground",  path: "/tool-playground", component: ToolPlaygroundPage, category: "catalog" },
  { id: "plugins",         labelKey: "tab.plugins",         path: "/plugins",         component: PluginsPage,        category: "catalog" },
  { id: "channels",        labelKey: "tab.channels",        path: "/channels",        component: ChannelsPage,       category: "catalog" },

  // ----- Operate -----
  { id: "analytics",     labelKey: "tab.analytics",     path: "/analytics",     component: AnalyticsPage,     category: "operate" },
  { id: "observability", labelKey: "tab.observability", path: "/observability", component: ObservabilityPage, category: "operate" },
  { id: "health",        labelKey: "tab.health",        path: "/health",        component: HealthPage,        category: "operate" },
  { id: "system",        labelKey: "tab.system",        path: "/system",        component: SystemPage,        category: "operate" },
  { id: "system-prompt", labelKey: "tab.systemPrompt",  path: "/system-prompt", component: SystemPromptPage,  category: "operate" },
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

/** Visible tabs grouped by category, in declared category order. */
export function tabsByCategory(): Array<{ category: TabCategory; tabs: ConsoleTab[] }> {
  const order: TabCategory[] = ["run", "history", "catalog", "operate"];
  const buckets: Record<TabCategory, ConsoleTab[]> = {
    run: [],
    history: [],
    catalog: [],
    operate: [],
  };
  for (const tab of TABS) {
    if (tab.hidden) continue;
    buckets[tab.category ?? "run"].push(tab);
  }
  return order.map((category) => ({ category, tabs: buckets[category] }));
}
