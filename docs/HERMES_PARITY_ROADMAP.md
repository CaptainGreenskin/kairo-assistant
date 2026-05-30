# Kairo → Hermes Capability Parity Roadmap

> Goal: bring **kairo-assistant** (Java / Spring Boot, built on the Kairo
> framework) to capability parity with **hermes-agent** (Python). This
> document is the planning artifact — a phased, estimated, dependency-aware
> breakdown. It is derived from a source-verified two-sided capability
> inventory taken on **2026-05-30**.

## Scope & framing

- "Parity" here means *functional capability* parity, not line-for-line
  reimplementation. Where Hermes ships 28 model providers via plugins, parity
  means "an equivalent pluggable provider mechanism with the providers our
  users need", not "all 28".
- **Placement rule:** capabilities that are generic agent-runtime concerns
  (context compression, async run handles, memory providers, model providers)
  belong **upstream in the Kairo framework** (`io.kairo.*`), with
  kairo-assistant consuming them. App-specific surfaces (channels, REST
  controllers, CLI commands, integration tools) live **in kairo-assistant**.
  Each workstream below is tagged `[framework]` or `[app]` accordingly.
- Effort sizing is T-shirt: **S** ≈ ≤1d, **M** ≈ 2–4d, **L** ≈ 1–2wk,
  **XL** ≈ 3wk+. These assume one engineer and exclude review/QA latency.

## Current parity snapshot

kairo already covers the **core agent loop + base toolset**: files/shell/code,
browser automation, memory + search, cron, sub-agents/delegate, skills +
skill_hub, plugins + marketplace, hooks, guardrails, MCP (client + server),
OpenAI-compatible chat, 5 channels, replay, profiles, evolution/curator (via
framework + `EvolutionRestController`), ACP (`.acp/agent.json`), i18n (en/zh).

Hermes is materially ahead on: **web search, async Runs API + approvals,
context compression, steer/queue/handoff, memory provider ecosystem,
multi-agent Kanban, channel breadth, integration tools, model-provider
breadth, and a long tail (RL pipeline, personalities, achievements,
observability, localization, multi terminal backends).**

---

## Phase 0 — Foundations & quick wins (parallelizable, low risk)

| # | Workstream | Tag | Effort | Notes |
|---|---|---|---|---|
| 0.1 | `web_search` / `web_extract` tool (pluggable backend: Brave/Tavily/SerpAPI) | app | M | Highest "feels smarter" ROI; kairo only has `web_fetch`. |
| 0.2 | Confirm `expert_team` vs Hermes `mixture_of_agents` semantics; close the gap or rename | app | S | May already be near-parity — verify first. |
| 0.3 | CLI command parity batch 1: `/approve` `/deny` `/yolo` `/reasoning` `/fast` | app | M | Maps to existing permission/guardrail + model config. |
| 0.4 | i18n breadth: extract hardcoded strings, add locale scaffolding | app | M | kairo has en/zh only; make adding locales mechanical. |

**Milestone M0:** web search live + MoA confirmed + approval/yolo commands.
**Acceptance:** agent can search the web and stream cited results; `/approve`
and `/yolo` gate destructive tools end-to-end.

---

## Phase 1 — P0 core agent parity (the headline gaps)

### 1.1 Web search productionization `[app]` — depends on 0.1 — **M**
Multiple providers, result ranking, `web_extract` for full-page read, X/Twitter
search optional. Acceptance: parity with Hermes `web_search`/`web_extract`.

### 1.2 Context compression — **ALREADY DONE (verified 2026-05-30)** ✅
This was a **false gap** in the original analysis. The Kairo framework already
ships a multi-level compaction system (`io.kairo.core.context.compaction`:
`SnipCompaction` → `CollapseCompaction` → `AutoCompaction` (L4 LLM structured
9-dimension summary at ~95% pressure) → `PartialCompaction`, with
`CompactionTrigger` driven by token pressure and `HeuristicTokenEstimator`).
kairo-assistant **already wires it** in `AssistantAgentFactory` via
`CompactionThresholds` (trigger/snip/micro/collapse/auto/partial) +
`tokenBudget` + `compactionTrigger`. No work needed. Lesson: the framework is
richer than the external inventory assumed — re-validate each remaining gap
against framework source before building.

### 1.3 Structured Runs API `[app]` — **MOSTLY DONE (2026-05-30)**
Built **app-level**, no framework change needed: `RunRegistry` (process-local,
bounded, per-run SSE sink + state machine PENDING→RUNNING→SUCCEEDED/FAILED/
STOPPED) + `RunController` exposing `POST /v1/runs` (`202 {run_id,status}`),
`GET /v1/runs/{id}`, `GET /v1/runs/{id}/events` (SSE lifecycle:
created/running/delta/terminal), `POST /v1/runs/{id}/stop` (interrupt +
dispose). Detached execution via existing `UnifiedGateway.route`; deltas
forwarded from `SessionAwareDeltaRouter`. 11 tests; full server suite green.
**Remaining:** `POST /v1/runs/{id}/approval` — deferred; needs approval-gating
in the agent loop (framework-level) so a run can pause AWAITING_APPROVAL.

### 1.4 Responses API `[app]` — depends on 1.3 — **M**
`/v1/responses` + `previous_response_id` chaining + `GET`/`DELETE`. Layer on
the Runs/session machinery. Acceptance: OpenAI Responses-API clients work
against kairo with response chaining.

### 1.5 steer / queue / handoff — **steer DONE (2026-05-30); queue/handoff pending**
- **steer ✅** — turned out the framework *already* owned the plumbing
  (`DefaultReActAgent.injectMessages` → `ReactLoop.injectMessages` into a
  `CopyOnWriteArrayList` history that the next iteration reads). One small,
  additive framework change: promoted `injectMessages` to the `Agent`
  interface as a **default no-op** method (`@since 1.3.0`) so callers needn't
  cast — verified non-breaking by recompiling `kairo-code` (exit 0). App
  surface: `POST /v1/runs/{id}/steer {input}` fetches the run's agent via
  `gateway.pool().get(key)` and injects; emits a `run.steered` SSE event.
  Guards: 404 unknown / 400 blank / 409 not-running. 3 tests; suites green.
- **queue ✅ (2026-05-30)** — `SessionRunQueue` serializes runs sharing a
  `sessionId`: `POST /v1/runs {input, sessionId}` runs immediately if the
  session is idle, else returns `status:"queued"` and waits FIFO; the next
  drains on the active run's terminal signal. New `QUEUED` run status +
  `run.queued` event. 5 queue tests + run-controller tests; suite green.
- **handoff ✅ (2026-05-30)** — `POST /v1/runs/handoff {sourceSessionId,
  sessionId?}` loads the source transcript from `ConversationStore`, converts
  to `Msg`, and seeds a (new or given) run session's agent via
  `gateway.pool().getOrCreate(key).injectMessages(history)` — reusing the
  steer plumbing. Returns `{session_id, seeded}`; the conversation continues
  via `POST /v1/runs {input, sessionId}` (with queue serialization). Guards:
  400 missing source / 404 unknown / 409 no replayable messages.
- **CLI `/steer`** — note: the current REPL blocks on `future.get()` during a
  turn, so it can't read `/steer` mid-generation without an async REPL rework.
  Steering is therefore exposed on the server (Runs API) where a second
  request can inject. CLI async-steer is a separate REPL refactor.

**Milestone M1:** Runs API + Responses API + context compression + steer/queue
shipped and documented. This is the bulk of "capability alignment" as users
perceive it.

---

## Phase 2 — P1 high-value systems

### 2.1 Memory system upgrade `[framework]` — **L**
SQLite + FTS5 full-text search behind the existing `MemoryStore` SPI, plus a
`MemoryProvider` plugin interface so external providers (mem0/honcho/etc.) can
be added later. Acceptance: FTS-backed `memory_search`; at least one external
provider wired as a reference.

### 2.2 Multi-agent Kanban `[app]` — **XL**
Durable SQLite board + dispatcher daemon (claim/promote/spawn workers, tenant
isolation, failure auto-block) + `kanban_*` tools + `/kanban` UI tab. Large,
self-contained. Acceptance: multiple worker agents coordinate through a shared
board with durable state.

### 2.3 Media tools `[app]` — **M**
`video_analyze`, `video_generate`, `computer_use` (desktop control). kairo has
image/vision/voice already. Acceptance: each tool round-trips against a real
provider.

### 2.4 Model provider breadth `[framework]` — **M, ongoing**
Audit kairo's `ProviderRegistry` coverage vs Hermes' ~28; add the
high-demand ones (gemini, deepseek, xai, glm/zai, kimi, qwen, ollama,
bedrock, azure). Acceptance: provider added via config, no code change.

### 2.5 CLI command parity batch 2 `[app]` — **M**
`/rollback` `/snapshot` `/background` `/agents` `/subgoal` `/kanban`
`/bundles` `/curator` `/insights`. Several map onto existing subsystems.

**Milestone M2:** memory FTS + media tools + provider breadth + Kanban MVP.

---

## Phase 3 — P2 breadth & long tail

| Workstream | Tag | Effort | Notes |
|---|---|---|---|
| Channel breadth: Discord, WhatsApp, Signal, Matrix, Mattermost, Email, SMS, WeCom, QQ, MS-Graph | app | XL (per-channel S/M) | Incremental; prioritize by user demand. |
| Integration tools: Home Assistant, Discord, Spotify, Yuanbao, Feishu doc/drive | app | L | Each is a self-contained tool bundle. |
| RL / training pipeline (tinker-atropos, trajectory gen) | app | XL | Niche; defer unless training is a goal. |
| Personalities/"soul", achievements, observability plugin | app/framework | L | Differentiators, not core. |
| Multi terminal backends (docker/ssh/modal/daytona) | app | L | kairo has shell + sandbox today. |
| skill taps / bundles, curator backups, NeuTTS, PTY web terminal | mixed | L | Polish tier. |

---

## Sequencing recommendation

```
M0 (Phase 0)  ──►  M1 (Phase 1: Runs/Responses/compression/steer)  ──►
M2 (Phase 2: memory FTS / media / providers / Kanban)  ──►  Phase 3 breadth
```

Rationale: Phase 0/1 move the *perceived* capability needle most and unblock
later work (Runs API underpins Responses + dashboard async + Kanban dispatch).
Phase 3 breadth is largely additive and can be demand-driven in parallel once
the core is aligned.

## Risks & open questions (verify before committing estimates)

1. **Framework boundary — RESOLVED (2026-05-30).** The Kairo framework source
   is local at `/Users/liulihan/IdeaProjects/sre/claude/kairo/` (`revision =
   1.0.0-SNAPSHOT`, exactly what this repo consumes), with capability modules
   under `kairo-capabilities/` (evolution, observability, multi-agent, …) and
   starters under `kairo-starters/`. So `[framework]` items can be done
   directly in source; `mvn install` there flows the SNAPSHOT into
   kairo-assistant. **Caveat:** the framework is ALSO consumed by
   `kairo-code/`, so framework changes have cross-repo blast radius — keep them
   additive/backward-compatible and rebuild both consumers.
2. **`UnifiedGateway` async model** — today runs are request-scoped; the Runs
   API needs a durable run registry + cancellation handle. Verify the pool's
   lifecycle supports detached runs.
3. **`expert_team` vs MoA — RESOLVED (2026-05-30).** `expert_team` does
   role-specialized parallel *task decomposition* (research+draft+review →
   consolidated output), i.e. orchestrator/delegate semantics. MoA is
   different: same query across *diverse models* in parallel + an aggregator
   LLM that synthesizes the best answer. So MoA is still a (small) gap —
   buildable on the existing delegate/gateway infra + an aggregator prompt.
   Reclassify as Phase 0/1, size **S**.
4. **Provider count** — enumerate kairo's current `ProviderRegistry` providers
   to size 2.4 accurately.
5. **Kanban scope** — Hermes' Kanban is large (dispatcher daemon + tenancy);
   define an MVP before committing to XL.

## Out of scope for "parity" (explicitly deferred)
RL training pipeline, NeuTTS local TTS, achievements gamification, and the
full 17-language localization unless product-prioritized.
