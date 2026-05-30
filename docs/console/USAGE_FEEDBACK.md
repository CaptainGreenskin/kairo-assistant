# Console v0.7 — Real-Usage Feedback

Captured during the live SMOKE walkthrough on 2026-05-21. Findings are
ranked by impact, with action notes for each.

> **Status update (2026-05-30):** A follow-up code audit confirmed the
> following are already resolved in the current tree and the sections
> below are kept only for the audit trail:
> - **F1** — `CronController` now runs `trigger` on a fire-and-forget
>   `triggerPool` executor (no longer blocks the HTTP thread).
> - **F2** — `CuratorAutoConfiguration` wiring fixed.
> - **F4** — page `<title>` updated.
> - **F7** — `ReplayController.preview()` now returns 404 for missing
>   sessions, matching the `export` endpoint.
> - **F8** — `GET /api/tools` exposes both `category` and `sideEffect`.
>
> **F12 (envelope unification) — done 2026-05-30:** every collection
> endpoint now returns `{ total, items, ...meta }` (cron/memory/skills/
> conversations + search/channels + recent/tools/sessions/plugins/
> subagents). Frontend `console.ts`/`types.ts`/pages updated to read
> `items`; verified via `tsc --noEmit` + `vite build`. NOT browser-tested
> visually. The error-status standardization half of F12 (200+`{error}`
> → 4xx) is still open — note the console's `request()` already throws on
> any `{error}` body, so it's a backend/API-consumer cleanup only.
>
> Still open: F3, F5, F6, F9–F11, F13–F15, and F12 error-status codes.

---

## 🔴 P0 — actual broken behavior

### F1. `/api/cron/{id}/trigger` blocks the HTTP thread until the agent finishes
**What I saw:** Pressing "Trigger" on a task with a fake API key froze
the request indefinitely. I had to `TaskStop` my smoke script.

**Root cause:** `CronController.trigger()` calls
`session.cronScheduler().trigger(taskId)` which is synchronous and
runs the full agent pass on the request thread. With a real model it
might be 5-60 seconds; with a broken key it never returns.

**Fix:** make trigger fire-and-forget. Push the actual outcome via a
`cron.fired` SSE event so the UI reflects success/failure without
holding the request hostage.

```java
@PostMapping("/{taskId}/trigger")
public Map<String, Object> trigger(@PathVariable String taskId) {
    var key = taskId;
    boolean enqueued = session.cronScheduler().triggerAsync(key);   // new
    if (enqueued) dashboard.cron("cron.triggering", key);
    return enqueued
            ? Map.of("status", "triggering", "id", key)
            : Map.of("error", "task not found", "id", key);
}
```

This is a real footgun — every user that clicks Trigger on a slow task
will see the UI freeze.

---

### F2. Three Spring-wiring bugs in `CuratorAutoConfiguration`
**What I saw:** Server refused to start with
`KAIRO_EVOLUTION_CURATOR_ENABLED=true` + `KAIRO_EVOLUTION_ENABLED=false`.

**Status:** ✅ Already fixed in commit `f2b2bb9`. Documented here for
the audit trail. (Missing `EvolutionController` bean + missing
`EvolutionProperties` config + missing `EvolvedSkillStore` fallback.)

---

## 🟡 P1 — bad UX or inconsistency

### F3. `mvn package -Pcron-ui` silently uses the dev template
**What I saw:** First package run used `-DskipTests` only; the JAR
shipped with a 12-line dev `index.html` that points at `/src/main.tsx`.
The UI loaded a blank page, console showed `500 /src/main.tsx`.

**Why it's silent:** `frontend-maven-plugin` is gated by the
`cron-ui` Maven profile **and** the `cron-ui` system property — both
must be set. `mvn package` alone copies the source `index.html` from
`src/main/webapp/cron/index.html` straight into the JAR without
running Vite, masking the omission.

**Fix:** two options, neither yet applied:
1. **Loud failure:** add a Maven check that errors if `index.html`
   references `/src/main.tsx` (i.e. unbuilt) instead of silently
   shipping it.
2. **Default to built:** flip the profile to `<activeByDefault>true</activeByDefault>`
   and let people who don't have Node opt out via `-P!cron-ui`. The
   built `dist/` is checked in anyway so it's only a problem when
   contributors first try to package the UI.

**Doc gap:** USER_GUIDE.md says `mvn package -Pcron-ui` but actually
needs `mvn package -Pcron-ui -Dcron-ui`. Quick fix.

---

### F4. Page title still says "Kairo Cron · Scheduled Tasks"
**What I saw:** Browser tab title hasn't been updated since the
rebrand from "Kairo Cron" to "Kairo Console". Lives in
`src/main/webapp/cron/index.html` line 6.

**Fix:** change to `<title>Kairo Console</title>`. 1-line.

---

### F5. `← Back to assistant` link probably 404s
**What I saw:** Top-right link `<a href="/">` points at the assistant
root, but there's no SPA fallback or route at `/` — only `/cron/*`.
I didn't actually click it during the smoke run (would've left the
SPA), but the wiring is suspicious.

**Fix:** Either (a) redirect to whatever the operator's landing page
is, (b) remove the link if there's nowhere meaningful to send them,
or (c) link to the WebSocket chat UI at a stable path.

---

### F6. Top nav overflows on narrow screens
**What I saw:** 21 tabs in a single row. On the 1280×800 viewport I
took screenshots with, only the first ~11 were visible without
horizontal scroll. The `overflow-x-auto` works mechanically but UX
is poor — users on 1366×768 laptops will hate it.

**Fix options** (pick one or compose):
1. Group tabs into category dropdowns: **Run · History · Catalog · Operate**
   (matching the TABS.md sections we already documented).
2. Show only the first 9 (digit-shortcut) tabs and put the rest
   behind a "•••" overflow button.
3. Move the nav to a left sidebar — typical IDE/Admin layout
   handles 30+ tabs without strain.

I'd go with #1 because we already have the categorization in our
documentation, so the user's mental model would be consistent.

---

### F7. `/api/replay/{id}/preview` returns HTTP 200 with `{"error": ...}` for missing sessions
**What I saw:** `curl /api/replay/non-existent/preview` → HTTP 200
plus a body that says it can't find the session. The sibling
`/api/replay/{id}?format=...` endpoint correctly returns 404. The
two should agree.

**Fix:** `preview` should also return 404. 4-line patch in
`ReplayController.preview()`.

---

### F8. Tool "side effect" badges show the wrong field
**What I saw:** Tools tab badges read `EXECUTION`, `FILE_AND_CODE`,
`INFORMATION` — those are tool *categories*, not *side effects*.
The actual side-effect enum on the server has values like `READ`,
`WRITE`, `DESTRUCTIVE`.

**Root cause:** I named the column "Side effect" in the UI but
sourced the data from `category`. Mismatch.

**Fix:** Either rename the column "Category" (cheaper) or wire
through the real `sideEffect` from the tool registry. The latter
needs a backend change — `GET /api/tools` returns category but not
sideEffect.

---

### F9. SSE badge shows "连接中" for ~1-2s on every page nav
**What I saw:** Footer flashes 黄色"连接中" → 绿色"实时" on initial
load. Not a bug, but it looks like a transient hiccup every time you
open the page.

**Fix:** Either (a) suppress the badge entirely until first
event/onopen fires (cleaner), or (b) seed the initial state as
`live` since `EventSource` is constructed eagerly (less accurate).
Tiny visual polish, low priority.

---

## 🟢 P2 — nice to have

### F10. Bundle is 67 MB (the Spring Boot fat JAR)
**Observation:** Not actionable per se — it's a fat JAR with all
deps inlined. Worth knowing for distribution discussions. Native
compilation via GraalVM could cut startup + size if it ever matters.

### F11. Server boot takes ~8 seconds
**Observation:** Spring Boot defaults. Fine for a long-running
process, painful for dev iteration if you hit it every test.
Mitigations: use `spring-boot:run` for hot reload, or split the
console into a standalone module that doesn't pull in the agent runtime.

### F12. API response shapes are inconsistent
**Observation:** Some endpoints return `{ total: N, items: [...] }`,
some return a bare array, some return `{ skills: [...] }`. The
frontend's `console.ts` has a runtime `Array.isArray()` check for
several of them.

**Fix:** Pick one envelope shape (`{ total, items }`) and migrate
endpoints to it. Low-effort, high-quality-of-life. Not blocking.

### F13. Theme picker icons have no tooltip
**Observation:** Top-right shows `☤ ◆ ▣` — pretty but cryptic.
The `title` attribute on `useTheme.tsx`'s buttons goes to the
parent `<div>` not the buttons themselves, so hover doesn't help.

**Fix:** Move `title={t("nav.theme")}` from the wrapper `<div>` to
each `<button>` and use `title={th.label}` for the individual
theme names.

### F14. Cron expression display in board cards is hard to read
**What I saw:** Card shows `*/5 * * * *` literally. A small humanizer
("Every 5 min") next to it would help non-cron-fluent operators.

**Fix:** The `describeCron()` function in `TaskCreatePage.tsx`
already does this for the form preview. Reuse it on the card.

### F15. Boot screen plays on every fresh tab
**Observation:** `sessionStorage` keys are per-tab. Open a second
tab and the splash plays again. Maybe move to `localStorage`?
But then operators never see it after first visit. Trade-off.

---

## ✅ What actually worked beautifully

- **SSE liveness**: created 3 cron tasks via `curl`, switched to the
  Board tab, saw all 3 cards instantly without a refresh button.
- **Cmd+K palette**: opened, typed nothing, navigated with arrows,
  picked a tab — buttery.
- **Theme cycle (`t`)**: hermes → midnight → terminal → hermes,
  every tab repaints correctly with the new palette. Tailwind
  CSS-vars trick paid off.
- **i18n auto-detection**: my browser is `zh-CN`, the UI came up
  in Chinese without me toggling anything. Header, tabs, status
  bar — all flipped.
- **Observability tab**: every endpoint hit during the smoke run
  showed up in the leaderboard with hit counts. Made the SSE
  invalidation visible (4 hits on `/api/events/stream` from the
  open page).
- **System tab state machine**: IDLE was correctly highlighted with
  a soft glow; pulse animation visible in the live page (lost in a
  static screenshot).
- **Tool execute**: `calculator` returned `2+2 = 4` instantly. The
  whole "Playground" loop works end-to-end.

---

## Action summary

| ID | Severity | Fix | Effort |
|---|---|---|---|
| F1 | 🔴 P0 | Make `trigger` fire-and-forget + SSE event | 1h backend + 30m frontend |
| F2 | 🔴 P0 | ✅ Done | — |
| F3 | 🟡 P1 | Loud failure when `cron-ui` not built + doc fix | 30m |
| F4 | 🟡 P1 | Update `<title>` | 1 line |
| F5 | 🟡 P1 | Fix / remove "Back to assistant" link | 15m |
| F6 | 🟡 P1 | Category dropdowns for nav (or sidebar) | 2-3h |
| F7 | 🟡 P1 | `/api/replay/{id}/preview` should 404 | 4 lines |
| F8 | 🟡 P1 | Fix sideEffect vs category labelling | 30m |
| F9 | 🟢 P2 | SSE badge initial state | 5m |
| F10-F15 | 🟢 P2 | Polish backlog | — |

Total P0+P1 effort: roughly **half a day** to address every real-usage
finding except F6 (the nav redesign). Worth scheduling.
