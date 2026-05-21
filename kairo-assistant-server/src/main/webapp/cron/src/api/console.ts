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
export interface ChannelRecentMessage {
  direction: "in" | "out";
  destination: string;
  content: string;
  timestamp: string;
  success: boolean;
}
export interface ChannelRecentResponse {
  channelId: string;
  total: number;
  messages: ChannelRecentMessage[];
}
export const channelsApi = {
  list: () => api.get<unknown>("/api/channels"),
  recent: (channelId: string, limit = 50) =>
    api.get<ChannelRecentResponse>(
      `/api/channels/${encodeURIComponent(channelId)}/recent?limit=${limit}`,
    ),
  send: (channelId: string, destination: string, content: string) =>
    api.post<{ sent?: boolean; error?: string }>(
      `/api/channels/${encodeURIComponent(channelId)}/send`,
      { destination, content },
    ),
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

/** ----- Tool execution history ----- */
export interface ToolHistoryEntry {
  tool: string;
  timestamp: string;
  durationMs: number;
  success: boolean;
  error?: string;
}
export interface ToolHistoryResponse {
  totalCalls: number;
  totalErrors?: number;
  avgDurationMs?: number;
  calls: ToolHistoryEntry[];
  note?: string;
}
export const toolHistoryApi = {
  list: (limit = 50) =>
    api.get<ToolHistoryResponse>(`/api/tools/history?limit=${limit}`),
};

/** ----- System info + Agent state ----- */
export interface SystemInfo {
  javaVersion?: string;
  javaVendor?: string;
  os?: string;
  osVersion?: string;
  arch?: string;
  processors?: number;
  memoryUsedMB?: number;
  memoryTotalMB?: number;
  memoryMaxMB?: number;
  activeThreads?: number;
  userDir?: string;
  userHome?: string;
  fileEncoding?: string;
}
export interface AgentState {
  state?: string;
  agentId?: string;
  agentName?: string;
}
export const systemApi = {
  info: () => api.get<SystemInfo>("/api/system"),
  agent: () => api.get<AgentState>("/api/agent/state"),
};

/** ----- Replay (redacted session exports) ----- */
export interface ReplayPreview {
  sessionId: string;
  title?: string;
  entries: Array<Record<string, unknown>>;
  generatedAt: string;
  note?: string;
}
export const replayApi = {
  preview: (sessionId: string) =>
    api.get<ReplayPreview>(`/api/replay/${encodeURIComponent(sessionId)}/preview`),
  /** Download URL for a given format — wire to an `<a download>` */
  downloadUrl: (sessionId: string, format: "json" | "markdown" | "html") =>
    `/api/replay/${encodeURIComponent(sessionId)}?format=${format}`,
};

/** ----- Session timeline (for Trace tab) ----- */
export interface SessionExportResponse {
  sessionId: string;
  format: string;
  content: string; // JSON-encoded array when format=json
}
export const traceApi = {
  exportJson: (sessionId: string) =>
    api.get<SessionExportResponse>(
      `/api/sessions/${encodeURIComponent(sessionId)}/export?format=json`,
    ),
};

/** ----- Tool playground ----- */
export interface ToolExecuteResponse {
  tool: string;
  success?: boolean;
  content?: unknown;
  error?: string;
}
export const toolExecuteApi = {
  run: (tool: string, args: Record<string, unknown>) =>
    api.post<ToolExecuteResponse>("/api/tools/execute", { tool, args }),
};

/** ----- System prompt editor ----- */
export interface SystemPromptResponse {
  content: string;
  path?: string;
  note?: string;
}
export const systemPromptApi = {
  get: () => api.get<SystemPromptResponse>("/api/system-prompt"),
  put: (content: string) =>
    fetch("/api/system-prompt", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content }),
    }).then((r) => r.json() as Promise<{ status?: string; error?: string }>),
};

/** ----- Plugin mutations ----- */
/** ----- Observability ----- */
export interface LatencyAnalytics {
  percentiles?: Record<string, number>;
  totalAgentCalls?: number;
  totalDurationMs?: number;
  avgDurationMs?: number;
}
export interface EndpointAnalytics {
  endpoints?: Record<string, number>;
}
export const observabilityApi = {
  metricsText: () =>
    fetch("/api/metrics").then((r) => {
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return r.text();
    }),
  latency: () => api.get<LatencyAnalytics>("/api/analytics/latency"),
  endpoints: () => api.get<EndpointAnalytics>("/api/analytics/endpoints"),
};

export const pluginActionsApi = {
  enable: (id: string) =>
    api.post<{ status?: string; error?: string }>(
      `/api/plugins/${encodeURIComponent(id)}/enable`,
      {},
    ),
  disable: (id: string) =>
    api.post<{ status?: string; error?: string }>(
      `/api/plugins/${encodeURIComponent(id)}/disable`,
      {},
    ),
  uninstall: (id: string) =>
    api.del<{ status?: string; error?: string }>(
      `/api/plugins/${encodeURIComponent(id)}`,
    ),
  installGitHub: (ownerRepo: string, ref?: string, scope: "USER" | "PROJECT" = "USER") =>
    api.post<{ status?: string; id?: string; name?: string; error?: string }>(
      "/api/plugins/install",
      { source: { type: "github", ownerRepo, ref: ref || undefined }, scope },
    ),
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
