import { api } from "./client";

export type SkillState = "ACTIVE" | "STALE" | "ARCHIVED";
export type SkillProvenance = "AGENT_CREATED" | "BUNDLED" | "HUB" | "MANUAL";

export interface EvolvedSkillView {
  name: string;
  description?: string;
  category?: string;
  tags?: string[];
  trustLevel?: string;
  createdAt?: string;
  updatedAt?: string;
  usageCount?: number;
  // Telemetry
  state?: SkillState;
  provenance?: SkillProvenance;
  pinned?: boolean;
  useCount?: number;
  viewCount?: number;
  patchCount?: number;
  lastUsedAt?: string;
  lastViewedAt?: string;
  lastPatchedAt?: string;
  archivedAt?: string;
  absorbedInto?: string;
}

export interface CuratorRunResponse {
  dryRun: boolean;
  totalChanged: number;
  lifecycle?: {
    checked: number;
    markedStale: string[];
    archived: string[];
    reactivated: string[];
  };
  consolidation: {
    dryRun: boolean;
    runAt: string;
    applied: ConsolidationStep[];
    skipped: ConsolidationStep[];
  };
}

export interface ConsolidationStep {
  kind: string;
  umbrella: string;
  applied: boolean;
  noop: boolean;
  message: string;
  siblings?: string[];
  sibling?: string;
  supportKind?: string;
  fileName?: string;
  description?: string;
  rationale?: string;
}

export interface LifecycleRunResponse {
  runAt: string;
  checked: number;
  markedStale: string[];
  archived: string[];
  reactivated: string[];
  skippedImmune: string[];
  totalChanged: number;
}

export const evolutionApi = {
  listSkills: () =>
    api.get<{ total: number; skills: EvolvedSkillView[] }>("/api/evolution/skills"),
  pin: (name: string) =>
    api.post<EvolvedSkillView>(`/api/evolution/skills/${encodeURIComponent(name)}/pin`, {}),
  unpin: (name: string) =>
    api.post<EvolvedSkillView>(`/api/evolution/skills/${encodeURIComponent(name)}/unpin`, {}),
  archive: (name: string) =>
    api.post<EvolvedSkillView>(`/api/evolution/skills/${encodeURIComponent(name)}/archive`, {}),
  runCurator: (dry: boolean) =>
    api.post<CuratorRunResponse>(`/api/evolution/curator/run?dry=${dry}`, {}),
  runLifecycle: () =>
    api.post<LifecycleRunResponse>(`/api/evolution/curator/lifecycle/run`, {}),
};
