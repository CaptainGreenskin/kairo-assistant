# Kairo Console — User Guide

The Kairo Console is the browser-based admin + observability UI bundled
with the Kairo Assistant server. It runs at `/cron/` (single-page app)
and talks to the same `/api/*` endpoints the REPL and CLI use, so
everything you see there reflects the live state of the running assistant.

---

## Installing & launching

The console ships with the server JAR — no separate install. A plain
`mvn package` runs Vite during the build and bundles the assets into
the JAR's classpath:

```bash
cd kairo-assistant-server
mvn package -DskipTests
java -jar target/kairo-assistant-server-*.jar
```

Then open: <http://localhost:8080/cron/>

The `cron-ui` Maven profile is active by default. Opt out with
`mvn package -P!cron-ui` when Node isn't installed on the build host;
the last committed `dist/` under `target/classes/static/cron/` is
preserved so the JAR still ships a working UI.

---

## Environment variables

Most knobs are set via env vars before launching the server:

| Var | Purpose | Default |
|---|---|---|
| `KAIRO_SERVER_PORT` | HTTP port | `8080` |
| `KAIRO_EVOLUTION_ENABLED` | Master switch for the evolution module | `false` |
| `KAIRO_EVOLUTION_CURATOR_ENABLED` | Enables the lifecycle curator daemon + REST | `false` |
| `KAIRO_EVOLUTION_CURATOR_AUTOSTART` | Auto-starts the curator at boot | `false` |
| `DINGTALK_WEBHOOK_URL` / `DINGTALK_SECRET` | DingTalk channel credentials | unset |
| `FEISHU_WEBHOOK_URL` | Feishu channel webhook | unset |

See **System Prompt** tab for the path of the `custom-instructions.md`
file that gets prepended to every system prompt.

---

## Tab tour

Two panes you'll touch every day, plus a long tail of inspectors. See
[TABS.md](./TABS.md) for the one-paragraph breakdown of each tab and
which API endpoints it hits.

The nav order is grouped by workflow stage:

1. **Run** — Dashboard · Chat · Tasks · Board · New Task
2. **History** — Evolution · Sessions · Replay · Trace · Memory
3. **Catalog** — Skills · Tools · Tool History · Playground · Plugins · Channels
4. **Operate** — Analytics · Observability · Health · System · Prompt

---

## Keyboard shortcuts

The console is designed for the keyboard. Click `?` in the top-right
or press `?` anywhere outside an input to see the full reference.

| Key | Action |
|---|---|
| `1` – `9` | Switch to tab by position |
| `⌘K` / `Ctrl+K` | Open command palette (fuzzy filter every tab + global actions) |
| `t` | Cycle theme (hermes → midnight → terminal) |
| `l` | Toggle language (en ↔ zh) |
| `?` | Open this keyboard help overlay |
| `Esc` | Close any open modal / palette / help |
| `Shift+Enter` (chat) | Newline instead of send |

Shortcuts are inert while you're typing in `<input>` / `<textarea>` /
contentEditable elements.

---

## Themes

Three built-in themes, swappable via the icon picker in the top-right
or the `t` shortcut. Persisted to `localStorage`.

| Theme | Vibe |
|---|---|
| **Hermes** (default ☤) | Calm cyan / teal — inspired by the Hermes HUD |
| **Midnight** (◆) | Original Kairo dark palette |
| **Terminal** (▣) | Green-on-black retro |

All colors flow through CSS variables in `src/index.css`; add a new
theme by registering it in `src/hooks/useTheme.tsx` and adding a
`[data-theme="<id>"]` block.

---

## Real-time updates (SSE)

The console subscribes to `/api/events/stream` over Server-Sent Events
and uses each event to invalidate the matching React-Query cache. So
when you create a cron task in the REPL or another tab, the task list
re-fetches within a second instead of waiting for the 60-second
fallback poll.

The footer shows the connection status as a colored badge:

- **● live** — connected, receiving events
- **connecting** — reconnecting after a hiccup (browser handles it)
- **offline** — `EventSource.readyState === CLOSED`

Events come from `KairoEventBus` server-side. The published domains
today are `cron` and `evolution`; add new domains by publishing through
`DashboardEventPublisher` in the server and listing them in
`useEventStream.ts`'s `INVALIDATE_MAP`.

---

## "Safe Share Mode" (Replay tab)

The **Replay** tab exports any conversation as JSON / Markdown / HTML
with a redactor pre-pass that scrubs:

- API keys (sk-…, pk-…, gh_pat_…, AWS, anthropic-ant-…)
- OpenAI / Anthropic bearer tokens (`Bearer …`)
- JWTs (three base64 segments)
- Emails
- POSIX & Windows absolute paths
- UUIDs (preserved length)

Each match is replaced with a typed token (`[REDACTED_KEY]`,
`[REDACTED_EMAIL]`, etc.) so reviewers can see what kind of secret was
scrubbed. Server-side code in `SessionRedactor.java` — extend the
`RULES` array there to add more patterns. Production deployments should
plug in `kairo-security-pii`'s full NER scanner for proper PII handling.

---

## FAQ

**Q: I added a cron task in the REPL but the table is empty.**
Reload the page once — the console caches the initial fetch. If it
still doesn't show, check the footer: an `offline` status means SSE is
broken and React-Query is using the 60s fallback poll.

**Q: Where do the conversation files live?**
Under `${KAIRO_DATA_DIR}/conversations/web/<sessionId>/`. The path is
visible in the **System** tab → Paths → Working dir.

**Q: Can I change which port the console runs on?**
Yes — set `KAIRO_SERVER_PORT`. The console is just static assets served
from the JAR, so they ride whatever port the assistant server uses.

**Q: I want a new tab. How?**
1. Write a React component under `src/pages/MyTab.tsx`.
2. Register it in `src/lib/console/tabs.tsx` (one entry: id / labelKey /
   path / component).
3. Add a `tab.myTab` label to BOTH `en` and `zh` in
   `src/i18n/translations.ts`.

The shell discovers the new tab automatically — no edit to App.tsx, no
edit to the nav, no edit to the router.

**Q: How do I disable a tab without removing it?**
Set `hidden: true` on its registry entry. The route stays mounted (so
deep links work) but the nav hides it.

**Q: Bundle size?**
~100 KB gzip JS + 5 KB gzip CSS as of v0.6. We're nowhere near needing
code splitting yet.
