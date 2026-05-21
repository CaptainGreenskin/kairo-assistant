# Kairo Console — Tab Reference

One paragraph per tab + the endpoints it hits, in nav order. Use this
when wiring a new tab or debugging why a panel is empty.

---

## Run

### Dashboard `/dashboard`
Executive overview — reuses the cron + evolution React-Query data
(no extra fetch), shows tasks/paused/failing/skills/stale/archived
counts at a glance. **Endpoints:** none of its own.

### Chat `/chat`
Real interactive chat with the assistant. Streams tokens via
`POST /api/chat/stream` (SSE-style `data:` frames: delta / response /
done / error parsed with `fetch` + `ReadableStream` — `EventSource`
can't do POST). Session id persisted in `localStorage`, rotatable via
"New session". Interrupt cancels the AbortController **and** posts to
`/api/chat/interrupt`. **Endpoints:** `POST /api/chat/stream`,
`POST /api/chat/interrupt`.

### Tasks `/`
The original table view — every cron task with status / next-run /
last-fired / failures. Click a row to open the detail drawer.
**Endpoints:** `GET /api/cron`, plus the mutation set
(`PUT /{id}` / `POST /{id}/pause|resume|trigger` / `DELETE /{id}`).

### Board `/board`
Kanban view of the same tasks, grouped by health: Failing / Active /
One-shot / Paused. Hover any card for inline fire / pause / resume.
**Endpoints:** same as Tasks (it's a different projection of the
same data).

### New Task `/create`
Form-driven cron task creation with chip suggestions for common
schedules (`0 9 * * *`, `every 5m`, …) + skill picker + chain-from
selector + delivery directives. **Endpoints:** `POST /api/cron`.

---

## History

### Evolution `/evolution`
The agent's evolved skills with telemetry: state (Active / Stale /
Archived), provenance (Agent / Bundled / Hub / Manual), use / view /
patch counters, pin / archive actions, curator dry-run + live-run
buttons. **Endpoints:** `GET /api/evolution/skills`, plus
`POST /skills/{name}/pin|unpin|archive`, `POST /curator/run?dry=…`,
`POST /curator/lifecycle/run`.

### Sessions `/sessions`
Master/detail conversation browser. Left column lists sessions
(optionally filtered by search); right column shows the message
thread + export-markdown button. **Endpoints:**
`GET /api/conversations`, `GET /api/conversations/{id}`,
`GET /api/conversations/search?q=…`.

### Replay `/replay`
Redacted export pipeline (Safe Share Mode). Pick a session → see the
redacted JSON preview → download as JSON / Markdown / HTML.
**Endpoints:** `GET /api/replay/{id}/preview`,
`GET /api/replay/{id}?format=json|markdown|html`.

### Trace `/trace`
Session timeline — same data as Sessions but rendered as a swim-lane
with numbered nodes, role-colored markers, and `+Δs` deltas. Useful
for spotting where the agent stalled. **Endpoints:**
`GET /api/sessions/{id}/export?format=json`.

### Memory `/memory`
The agent's persistent knowledge store, grouped by scope (GLOBAL /
SESSION / AGENT / USER / TASK). Inline search, expandable add-entry
form with importance slider, two-click delete on hover. **Endpoints:**
`GET /api/memory?scope=…`, `GET /api/memory/search`,
`POST /api/memory`, `DELETE /api/memory/{id}`.

---

## Catalog

### Skills `/skills`
Static `SkillRegistry` catalog grouped by category — distinct from
Evolution (which is agent-evolved with telemetry). **Endpoints:**
`GET /api/skills`.

### Tools `/tools`
Every registered tool — filterable table with side-effect badges
(READ / WRITE / DESTRUCTIVE), tinted by risk. **Endpoints:**
`GET /api/tools`.

### Tool History `/tool-history`
Recent tool invocations as recorded by ToolCallLogger — reverse
chronological, filterable by success/error/tool name. 5s refresh.
**Endpoints:** `GET /api/tools/history?limit=100`.

### Playground `/tool-playground`
Hand-run any tool with arbitrary JSON args. Destructive tools get a
red badge + confirm() before running. **Endpoints:**
`POST /api/tools/execute`.

### Plugins `/plugins`
Installed plugins (skills / hooks / MCP servers / bin commands).
Two-column card grid + per-card enable / disable / uninstall + a
top "Install from GitHub" form. **Endpoints:**
`GET /api/plugins`, `POST /api/plugins/{id}/enable|disable`,
`DELETE /api/plugins/{id}`, `POST /api/plugins/install`.

### Channels `/channels`
External integrations (DingTalk, Feishu, …). Master/detail:
click a channel → send-test form + recent-traffic log (in-memory
ring buffer of last 50 messages, refreshing every 5s).
**Endpoints:** `GET /api/channels`,
`POST /api/channels/{id}/send`, `GET /api/channels/{id}/recent`.

---

## Operate

### Analytics `/analytics`
Tokens (input/output/total + USD estimate), tool call leaderboard,
session/message counts, model + provider. 15s refresh.
**Endpoints:** `GET /api/analytics`, `/api/analytics/tokens`,
`/api/analytics/tools`.

### Observability `/observability`
SRE-style — latency percentiles (p50/p95/p99), endpoint hit
leaderboard with share bars, and a collapsible raw Prometheus dump
ready to scrape. 15-30s refresh. **Endpoints:**
`GET /api/metrics` (text/plain), `/api/analytics/latency`,
`/api/analytics/endpoints`.

### Health `/health`
Server liveness, JVM heap, uptime. 10s refresh. **Endpoints:**
`GET /api/health/detailed`.

### System `/system`
JVM + OS environment + current agent state + a visual state-machine
diagram (IDLE → REASONING → ACTING → WAITING → DONE ⇄ ERROR) with
the current node pulsing. 30s refresh on system info, 10s on agent.
**Endpoints:** `GET /api/system`, `GET /api/agent/state`.

### Prompt `/system-prompt`
View + edit `custom-instructions.md` (the static prefix prepended
to every system prompt). Char / line / approx-token counts shown
live. **Endpoints:** `GET /api/system-prompt`,
`PUT /api/system-prompt`.

---

## Adding a tab

```ts
// src/lib/console/tabs.tsx
import { MyPage } from "../../pages/MyPage";

export const TABS: ConsoleTab[] = [
  // … existing tabs
  {
    id: "my-tab",
    labelKey: "tab.myTab",
    path: "/my-tab",
    component: MyPage,
  },
];
```

Then add `"tab.myTab"` to both `en` and `zh` dictionaries in
`src/i18n/translations.ts`. That's it — the nav, the router, the
keyboard shortcut, and the Cmd+K palette all pick it up.
