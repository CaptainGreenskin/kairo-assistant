# Kairo Console — Smoke Checklist

Manual happy-path walk-through to run after a deploy. Should take ~5
minutes if everything is healthy.

---

## Pre-flight

```bash
# Build (cron-ui profile is active by default; opt out with -P!cron-ui)
cd kairo-assistant-server
mvn package -DskipTests

# Start
java -jar target/kairo-assistant-server-*.jar

# Smoke
curl -s http://localhost:8080/api/health | jq .       # → {"status":"ok"}
curl -s http://localhost:8080/cron/ | head -1          # → <!DOCTYPE html>
```

Open <http://localhost:8080/cron/> and confirm the boot screen plays
through, then the Dashboard tab loads with `live` in the footer badge.

---

## Tab-by-tab (1-9 + Cmd+K)

For each tab below: navigate to it (digit shortcut or click), confirm
no console errors, confirm the headline metric / list / form renders.

| # | Tab | What "OK" looks like |
|---|---|---|
| 1 | Dashboard | 3 cron cards + 4 evolution cards, no zero except real-zero |
| 2 | Chat | "Send a message to begin." Test: type `hello` + Enter → tokens stream in, then "● done" |
| 3 | Tasks | Table renders (empty is fine). Click a row → drawer opens with status block at top |
| - | Board | 4 columns (Failing / Active / One-shot / Paused). At least one column shows tasks if any exist |
| - | New Task | Schedule chips render. Try `every 5m` + a prompt → toast confirms; new task appears on Tasks tab within 1s (SSE) |
| - | Evolution | Renders even if empty. If skills exist, pin/archive buttons fire toasts |
| - | Sessions | If conversations exist, clicking one shows messages + export-md link works |
| - | Replay | Pick a session → JSON preview shows redacted content (look for `[REDACTED_*]` if any sensitive substrings exist) |
| - | Trace | Same session source as Replay → timeline renders numbered nodes, +Δs labels |
| - | Memory | Scope chips switch; add an entry; delete-on-hover works |
| - | Skills | Catalog grouped by category renders |
| - | Tools | Filter typing narrows the list |
| - | Tool History | 5s refresh works; recent tool calls show row-by-row |
| - | Playground | Pick `BashTool` (if registered) → `{"command":"echo hi"}` → result panel shows |
| - | Plugins | Empty-state copy if none installed; Install form expands; collapsed by default |
| - | Channels | Pick a channel → Send test form + Recent messages log render |
| - | Analytics | Token / cost / latency cards render even if zero |
| - | Observability | Prometheus dump expands; endpoint leaderboard shows /api/* hits incrementing |
| - | Health | Status: ok + heap stats render |
| - | System | Java/OS card + state machine row with one node pulsing |
| - | Prompt | Textarea loads file content; char/line/token counts update as you type |

---

## Global behaviors

- **SSE liveness**: create a cron task in one tab, watch Tasks/Dashboard reflect it in another tab without polling
- **Theme cycle**: press `t` three times — should land back on Hermes
- **Language**: press `l` — every label flips to Chinese, `l` again flips back
- **Cmd+K palette**: open, type "evo", Enter → Evolution tab opens
- **Help overlay**: press `?` → modal lists every shortcut, Esc closes
- **Boot screen**: clear sessionStorage and reload → splash plays once

---

## When something breaks

| Symptom | Likely cause | Fix |
|---|---|---|
| `offline` badge in footer | SSE endpoint 4xx/5xx | Check `KAIRO_EVOLUTION_*` env vars, restart server |
| Empty Tasks / Evolution table | Backend tests passed but session has no data | Normal for first boot — create one via REPL or New Task |
| Channels tab shows "no channels" | DINGTALK / FEISHU env vars unset | Set creds, restart — channels are gated by env |
| Tool History "ToolCallLogger not active" | The ToolExecutor wired isn't logging | Check kairo-assistant-core's session config |
| Prompt tab content empty | File doesn't exist yet | Just type + Save — it'll be created at `${KAIRO_DATA_DIR}/custom-instructions.md` |
| Cmd+K does nothing | Mac browser intercepting? Try Ctrl+K | Both modifiers are bound |
| Plugin install times out | GitHub rate limit hit | Set `GH_TOKEN`, or wait an hour |
