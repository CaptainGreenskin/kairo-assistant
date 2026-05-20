// Mirrors io.kairo.api.cron.CronTask (with the M2/M3 status fields the
// controller now exposes). Fields are optional/nullable per the JSON shape
// CronController#toView produces.
export interface CronTaskView {
  id: string;
  cron: string;
  prompt: string;
  recurring: boolean;
  durable: boolean;
  paused: boolean;
  consecutiveFailures: number;
  lastError?: string;
  createdAt: string;
  lastFiredAt?: string;
  nextRunAt?: string;
  skills?: string[];
  workdir?: string;
  noAgent?: boolean;
  script?: string;
  contextFromTaskId?: string;
}

export interface CronTaskList {
  total: number;
  tasks: CronTaskView[];
}

export interface CreateCronTaskRequest {
  cron: string;
  prompt: string;
  recurring?: boolean;
  durable?: boolean;
  skills?: string[];
  workdir?: string;
  noAgent?: boolean;
  script?: string;
  contextFromTaskId?: string;
}

export interface EditCronTaskRequest {
  cron?: string;
  prompt?: string;
}

/** Available skill name (subset of /api/skills payload we care about). */
export interface SkillSummary {
  name: string;
  description?: string;
}

/** Generic backend error / status response. */
export interface ApiResult {
  status?: string;
  error?: string;
  id?: string;
}
