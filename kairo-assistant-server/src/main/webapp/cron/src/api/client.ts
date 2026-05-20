import type {
  ApiResult,
  CreateCronTaskRequest,
  CronTaskList,
  CronTaskView,
  EditCronTaskRequest,
  SkillSummary,
} from "./types";

/** Thin fetch wrapper that throws on non-2xx + parses JSON. */
async function request<T>(
  url: string,
  init?: RequestInit,
): Promise<T> {
  const resp = await fetch(url, {
    headers: { "Content-Type": "application/json" },
    ...init,
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
        Array.isArray((raw as { skills?: unknown[] }).skills)
      ) {
        return (raw as { skills: unknown[] }).skills.map((s) =>
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
