# Reusing the Console Shell in kairo-code

`kairo-code-web` already has its own React app (`AgentWebSocketHandler`
on the backend, chat-focused frontend). The Kairo Console shell — theme +
i18n + tab registry + keyboard + Cmd+K + SSE — is independent of any
assistant-specific code and could be lifted into kairo-code with ~1 day
of work.

This doc sketches the cleanest path. It does NOT propose extracting the
shell into a shared npm package (premature; only two consumers).

---

## What to copy verbatim

Drop these into `kairo-code-web/src/` unchanged:

```
src/components/
  ConsoleShell.tsx
  CommandPalette.tsx
  ShortcutHelp.tsx
  BootScreen.tsx

src/hooks/
  useTheme.tsx
  useKeyboardNav.ts
  useEventStream.ts        # optional — kairo-code already has its own WS

src/i18n/
  I18nProvider.tsx
  index.ts
  translations.ts          # rewrite the dictionary; keep the shape

src/lib/console/tabs.tsx   # rewrite the TABS array — keep ConsoleTab shape
```

Plus the CSS-variable theme tokens block in `src/index.css` and the
Tailwind config color-bindings (every palette name → `var(--kc-*)`).

---

## What to rewrite

### `tabs.tsx` — register kairo-code's pages instead

```ts
import { ChatPage } from "../../pages/ChatPage";          // already exists
import { SessionsPage } from "../../pages/SessionsPage";
import { CodebasePage } from "../../pages/CodebasePage";   // new
import { GitPage } from "../../pages/GitPage";             // new
// …

export const TABS: ConsoleTab[] = [
  { id: "chat",       labelKey: "tab.chat",     path: "/chat",     component: ChatPage },
  { id: "sessions",   labelKey: "tab.sessions", path: "/sessions", component: SessionsPage },
  { id: "codebase",   labelKey: "tab.codebase", path: "/codebase", component: CodebasePage },
  { id: "git",        labelKey: "tab.git",      path: "/git",      component: GitPage },
  // …
];
```

kairo-code has different REST surfaces (`/api/workspaces`,
`/api/git/*`, `/api/team/*`), so each page hits its own endpoints.
The shell doesn't care.

### `translations.ts` — kairo-code's vocabulary

Most `app.*` and `nav.*` keys carry over verbatim. Tab labels are
project-specific. The dictionary shape (`Record<Language, Record<string,
string>>`) and the type-driven `TranslationKey` must stay so the shell
catches missing keys at compile time.

### `useEventStream.ts` — pick one

kairo-code already has a session-bound WebSocket (`AgentWebSocketHandler`)
that's overloaded with both command and notification traffic. Two
choices:

- **Reuse the existing WS** — extend `AgentWebSocketHandler` to
  also broadcast lightweight "data changed" notifications, then write
  a simpler hook (no SSE) that subscribes to those.
- **Add a dedicated SSE endpoint** — port `AssistantEventStreamConfig`
  + `AssistantEventStreamController` from kairo-assistant. Spring MVC
  vs WebFlux constraint will be the same.

The first option is less code; the second separates command-channel
from dashboard-channel cleanly (the architectural decision we already
made for kairo-assistant — see CLAUDE.md note about 指挥 vs 看板).

---

## What likely doesn't carry over

- **EvolutionPage**, **MemoryPage**, **CronPage** — these are
  assistant-specific. kairo-code has its own equivalents (team panel,
  goals, expert team coordination) that fit different tabs.
- **CHat session-id rotation** — kairo-code's session model differs
  (workspace-bound, not browser-bound).
- **CronWebUiConfig** Spring forward — kairo-code's web stack already
  handles SPA routing.

---

## Build-time integration

If kairo-code-web is also Vite + React 19 + Tailwind, the import
should be drop-in. If not, adapt:

- **Vite + Tailwind**: copy `tailwind.config.js` color block + `index.css`
- **CRA / Webpack**: same files, different config — Tailwind support
  needed
- **Different React major**: should be fine — the shell uses no exotic
  features

Bundle impact: ~30-40 KB gzip for the shell alone (palette + boot +
theme + i18n + nav). Worth it given what you get.

---

## Effort estimate

| Step | Hours |
|---|---|
| Copy shell files + CSS tokens | 1 |
| Rewrite `tabs.tsx` for kairo-code routes | 2 |
| Rewrite `translations.ts` dictionary | 1 |
| Wire SSE (option A — reuse existing WS) | 2 |
| Visual QA + fixing color tokens that don't translate | 2 |
| **Total** | **~1 working day** |

Defer "extract into a shared npm package" until at least three
consumers exist (Rule of Three). For now, the shell is small enough
that a one-time copy is cheaper than the npm publish/version overhead.
