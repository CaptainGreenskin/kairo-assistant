import type {
  ApiResult,
  CreateCronTaskRequest,
  CronTaskList,
  CronTaskView,
  EditCronTaskRequest,
  SkillSummary,
} from "./types";

// API key handling for KAIRO_API_KEY-protected servers. On first load we look
// for ?api_key=<key> in the URL (so a magic link like
// http://host/?api_key=... bootstraps the session) and stash it in
// sessionStorage. Every fetch and EventSource URL then carries the key — either
// as Authorization: Bearer for fetch, or ?api_key= for SSE (EventSource can't
// set headers). Browser EventSource is the load-bearing reason this exists; pure
// header auth would lock the console out of live updates when API key is on.
const API_KEY_STORAGE = "kc-api-key";

function bootstrapApiKey(): void {
  try {
    const params = new URLSearchParams(window.location.search);
    const fromUrl = params.get("api_key");
    if (fromUrl && fromUrl.length > 0) {
      sessionStorage.setItem(API_KEY_STORAGE, fromUrl);
      params.delete("api_key");
      const cleaned =
        window.location.pathname +
        (params.toString() ? "?" + params.toString() : "") +
        window.location.hash;
      window.history.replaceState({}, "", cleaned);
    }
  } catch {
    // sessionStorage may be disabled (private window) — fall through, the
    // console just won't authenticate. That's the same as before.
  }
}
bootstrapApiKey();

export function getApiKey(): string | null {
  try {
    return sessionStorage.getItem(API_KEY_STORAGE);
  } catch {
    return null;
  }
}

/** Append ?api_key=<key> to a URL when a key is configured. For SSE / asset
 * URLs that can't carry an Authorization header. */
export function withApiKey(url: string): string {
  const key = getApiKey();
  if (!key) return url;
  const sep = url.includes("?") ? "&" : "?";
  return url + sep + "api_key=" + encodeURIComponent(key);
}

/** Thin fetch wrapper that throws on non-2xx + parses JSON. */
async function request<T>(
  url: string,
  init?: RequestInit,
): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...((init?.headers as Record<string, string>) || {}),
  };
  const key = getApiKey();
  if (key && !headers.Authorization) {
    headers.Authorization = "Bearer " + key;
  }
  const resp = await fetch(url, {
    ...init,
    headers,
  });
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status} ${resp.statusText}`);
  }
  const payload = (await resp.json()) as T & ApiResult;
  if (payload && (payload as ApiResult).error) {
    throw new Error((payload as ApiResult).error!);
  }
  return payload;
}

export const cronApi = {
  list: () => request<CronTaskList>("/api/cron"),

  create: (body: CreateCronTaskRequest) =>
    request<CronTaskView & ApiResult>("/api/cron", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  edit: (id: string, body: EditCronTaskRequest) =>
    request<CronTaskView & ApiResult>(`/api/cron/${id}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  pause: (id: string) =>
    request<CronTaskView & ApiResult>(`/api/cron/${id}/pause`, {
      method: "POST",
    }),

  resume: (id: string) =>
    request<CronTaskView & ApiResult>(`/api/cron/${id}/resume`, {
      method: "POST",
    }),

  trigger: (id: string) =>
    request<ApiResult>(`/api/cron/${id}/trigger`, { method: "POST" }),

  delete: (id: string) =>
    request<ApiResult>(`/api/cron/${id}`, { method: "DELETE" }),
};

export const skillsApi = {
  list: async (): Promise<SkillSummary[]> => {
    // The assistant exposes /api/skills returning the registered skill catalog.
    // Backend shape isn't fully strict-typed here — accept whatever, project to
    // the bits we need.
    try {
      const raw = await request<unknown>("/api/skills");
      if (Array.isArray(raw)) {
        return raw.map((s) => coerceSkill(s));
      }
      if (
        raw &&
        typeof raw === "object" &&
        Array.isArray((raw as { items?: unknown[] }).items)
      ) {
        return (raw as { items: unknown[] }).items.map((s) =>
          coerceSkill(s),
        );
      }
      return [];
    } catch {
      // Skills endpoint optional — if it's not present, the picker just shows
      // a free-text input instead.
      return [];
    }
  },
};

function coerceSkill(s: unknown): SkillSummary {
  if (typeof s === "string") return { name: s };
  const obj = s as Record<string, unknown>;
  return {
    name: String(obj.name ?? obj.id ?? ""),
    description:
      typeof obj.description === "string" ? obj.description : undefined,
  };
}

/** Generic HTTP helpers used by feature modules outside of cron. */
export const api = {
  get: <T>(url: string) => request<T>(url),
  post: <T>(url: string, body?: unknown) =>
    request<T>(url, {
      method: "POST",
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  del: <T>(url: string) => request<T>(url, { method: "DELETE" }),
};
