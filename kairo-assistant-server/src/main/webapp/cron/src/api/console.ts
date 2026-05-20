import { api } from "./client";

/** ----- Plugin Hub ----- */
export interface PluginView {
  id: string;
  name: string;
  version: string;
  description?: string;
  source: string;
  scope: string;
  enabled: boolean;
}
export const pluginsApi = {
  list: () => api.get<PluginView[]>("/api/plugins"),
};

/** ----- Memory ----- */
export type MemoryScope = "GLOBAL" | "SESSION" | "AGENT" | "USER" | "TASK";

export interface MemoryEntryView {
  id: string;
  content: string;
  scope: MemoryScope;
  importance: number;
  tags: string[];
  timestamp?: string;
  agentId?: string;
}
export interface MemoryListResponse {
  scope: MemoryScope;
  total: number;
  entries: MemoryEntryView[];
}
export const memoryApi = {
  list: (scope: MemoryScope) =>
    api.get<MemoryListResponse>(`/api/memory?scope=${scope}`),
  search: (q: string, scope: MemoryScope) =>
    api.get<MemoryListResponse & { query: string }>(
      `/api/memory/search?q=${encodeURIComponent(q)}&scope=${scope}`,
    ),
  save: (body: {
    content: string;
    scope: MemoryScope;
    importance?: number;
    tags?: string[];
  }) => api.post<{ status: string; id: string }>("/api/memory", body),
  delete: (id: string) =>
    api.del<{ status: string; id: string }>(`/api/memory/${id}`),
};

/** ----- Health ----- */
export interface HealthDetailedResponse {
  status: string;
  uptime?: string;
  uptimeSeconds?: number;
  memory?: { heapUsedMb?: number; heapMaxMb?: number; [k: string]: unknown };
  jvm?: Record<string, unknown>;
  channels?: Record<string, unknown>;
  [k: string]: unknown;
}
export const healthApi = {
  detailed: () => api.get<HealthDetailedResponse>("/api/health/detailed"),
};

/** ----- Analytics ----- */
export interface AnalyticsResponse {
  uptime?: number;
  totalSessions?: number;
  registeredTools?: number;
  registeredSkills?: number;
  plugins?: number;
  provider?: string;
  model?: string;
  tokens?: { inputTokens?: number; outputTokens?: number; totalTokens?: number };
  totalMessages?: number;
  durationPercentiles?: Record<string, number>;
  endpointHits?: Record<string, number>;
  tools?: {
    totalCalls?: number;
    totalErrors?: number;
    avgDurationMs?: number;
    totalDurationMs?: number;
  };
}
export interface TokenAnalyticsResponse {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  totalMessages: number;
  avgTokensPerMessage?: number;
  estimatedCostUsd: number;
  pricing: {
    model: string;
    inputPer1MTokens: number;
    outputPer1MTokens: number;
    note?: string;
  };
}
export interface ToolAnalyticsResponse {
  totalToolCalls: number;
  uniqueToolsUsed: number;
  tools: Record<string, number>;
}
export const analyticsApi = {
  overview: () => api.get<AnalyticsResponse>("/api/analytics"),
  tokens: () => api.get<TokenAnalyticsResponse>("/api/analytics/tokens"),
  tools: () => api.get<ToolAnalyticsResponse>("/api/analytics/tools"),
};

/** ----- Skills (registered catalog, not evolution) ----- */
export interface SkillCatalogEntry {
  name: string;
  category?: string;
  description?: string;
  version?: string;
  trustLevel?: string;
  metadata?: Record<string, unknown>;
}
export const skillsCatalogApi = {
  list: () =>
    api.get<{ total: number; skills: SkillCatalogEntry[] }>("/api/skills"),
};

/** ----- Channels ----- */
export interface ChannelInfo {
  id?: string;
  name?: string;
  type?: string;
  status?: string;
  [k: string]: unknown;
}
export const channelsApi = {
  list: () => api.get<unknown>("/api/channels"),
};

/** ----- Tools ----- */
export interface ToolEntry {
  name: string;
  description?: string;
  category?: string;
  sideEffect?: string;
  [k: string]: unknown;
}
export const toolsApi = {
  list: () =>
    api.get<{ tools?: ToolEntry[]; total?: number; [k: string]: unknown }>(
      "/api/tools",
    ),
  categories: () => api.get<unknown>("/api/tools/categories"),
};

/** ----- Conversations ----- */
export interface ConversationSummary {
  sessionId: string;
  title?: string;
  // Backend returns Map<String,String> with whatever metadata; keep loose.
  [k: string]: unknown;
}
export interface ConversationDetail {
  sessionId: string;
  title?: string;
  messageCount: number;
  messages: Array<{
    type?: string;
    role?: string;
    content?: unknown;
    timestamp?: string;
    [k: string]: unknown;
  }>;
}
export const conversationsApi = {
  list: () =>
    api.get<{ total: number; conversations: ConversationSummary[] }>(
      "/api/conversations",
    ),
  get: (sessionId: string) =>
    api.get<ConversationDetail>(`/api/conversations/${encodeURIComponent(sessionId)}`),
  search: (q: string) =>
    api.get<{
      query: string;
      sessionCount?: number;
      sessions?: Array<{ sessionId: string; title?: string; matchCount?: number }>;
      total?: number;
      results?: unknown[];
    }>(`/api/conversations/search?q=${encodeURIComponent(q)}`),
};
