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
