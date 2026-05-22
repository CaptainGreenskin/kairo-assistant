import { useEffect, useMemo, useRef, useState } from "react";
import { Copy, MessageSquarePlus, Pencil, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { getApiKey } from "../api/client";

/**
 * Interactive chat with a multi-conversation sidebar + per-message hover
 * actions + code-block copy. Persists everything to localStorage so a tab
 * reload preserves history.
 *
 * Backend wire-protocol (unchanged): POST /api/chat/stream emits SSE-style
 * data frames — delta / response / done / error.
 */
interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "error";
  content: string;
  streaming?: boolean;
  timestamp: number;
}

interface Conversation {
  id: string; // also used as X-Session-Id
  title: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
}

const STORAGE_KEY = "kc-chat-conversations";
const ACTIVE_KEY = "kc-chat-active";

function loadConversations(): Conversation[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as Conversation[]) : [];
  } catch {
    return [];
  }
}

function freshConversation(): Conversation {
  const now = Date.now();
  return {
    id: "web-" + Math.random().toString(36).slice(2, 10),
    title: "",
    messages: [],
    createdAt: now,
    updatedAt: now,
  };
}

export function ChatPage() {
  const [conversations, setConversations] = useState<Conversation[]>(() => {
    const existing = loadConversations();
    return existing.length > 0 ? existing : [freshConversation()];
  });
  const [activeId, setActiveId] = useState<string>(() => {
    const stored = localStorage.getItem(ACTIVE_KEY);
    const first = loadConversations()[0]?.id;
    return stored || first || freshConversation().id;
  });
  const [draft, setDraft] = useState("");
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  // Make sure activeId points to a real conversation.
  useEffect(() => {
    if (!conversations.some((c) => c.id === activeId)) {
      setActiveId(conversations[0]?.id ?? freshConversation().id);
    }
  }, [conversations, activeId]);

  // Persist on every change.
  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(conversations));
  }, [conversations]);
  useEffect(() => {
    localStorage.setItem(ACTIVE_KEY, activeId);
  }, [activeId]);

  const active = useMemo(
    () => conversations.find((c) => c.id === activeId) ?? conversations[0],
    [conversations, activeId],
  );

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [active?.messages.length]);

  const updateActive = (mut: (c: Conversation) => Conversation) => {
    setConversations((cs) => cs.map((c) => (c.id === activeId ? mut(c) : c)));
  };

  const send = async () => {
    const text = draft.trim();
    if (!text || streaming || !active) return;
    setDraft("");

    const userMsg: ChatMessage = {
      id: "u-" + Date.now(),
      role: "user",
      content: text,
      timestamp: Date.now(),
    };
    const assistantId = "a-" + Date.now();
    updateActive((c) => ({
      ...c,
      title: c.title || (text.length > 40 ? text.slice(0, 40) + "…" : text),
      messages: [
        ...c.messages,
        userMsg,
        { id: assistantId, role: "assistant", content: "", streaming: true, timestamp: Date.now() },
      ],
      updatedAt: Date.now(),
    }));
    setStreaming(true);

    const controller = new AbortController();
    abortRef.current = controller;
    const updateAssistant = (patch: Partial<ChatMessage>) =>
      updateActive((c) => ({
        ...c,
        messages: c.messages.map((m) => (m.id === assistantId ? { ...m, ...patch } : m)),
        updatedAt: Date.now(),
      }));

    try {
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        "X-Session-Id": active.id,
      };
      const apiKey = getApiKey();
      if (apiKey) headers.Authorization = "Bearer " + apiKey;
      const res = await fetch("/api/chat/stream", {
        method: "POST",
        headers,
        body: JSON.stringify({ message: text }),
        signal: controller.signal,
      });
      if (!res.body) throw new Error("no response body");

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let finalText = "";

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const frames = buffer.split("\n\n");
        buffer = frames.pop() ?? "";
        for (const frame of frames) {
          const dataLine = frame.split("\n").find((l) => l.startsWith("data:"));
          if (!dataLine) continue;
          const raw = dataLine.slice(5).trim();
          if (!raw) continue;
          let parsed: { type: string; content?: string; message?: string };
          try {
            parsed = JSON.parse(raw);
          } catch {
            continue;
          }
          if (parsed.type === "delta" && parsed.content) {
            finalText += parsed.content;
            updateAssistant({ content: finalText });
          } else if (parsed.type === "response" && parsed.content) {
            finalText = parsed.content;
            updateAssistant({ content: finalText, streaming: false });
          } else if (parsed.type === "done") {
            updateAssistant({ streaming: false });
          } else if (parsed.type === "error") {
            updateAssistant({
              role: "error",
              content: parsed.message ?? "error",
              streaming: false,
            });
          }
        }
      }
    } catch (e) {
      if ((e as Error).name !== "AbortError") {
        updateAssistant({
          role: "error",
          content: String((e as Error).message),
          streaming: false,
        });
      } else {
        updateAssistant({ streaming: false });
      }
    } finally {
      setStreaming(false);
      abortRef.current = null;
    }
  };

  const interrupt = async () => {
    abortRef.current?.abort();
    try {
      const headers: Record<string, string> = { "X-Session-Id": activeId };
      const apiKey = getApiKey();
      if (apiKey) headers.Authorization = "Bearer " + apiKey;
      await fetch("/api/chat/interrupt", { method: "POST", headers });
    } catch {
      // best-effort
    }
  };

  const newConversation = () => {
    const fresh = freshConversation();
    setConversations((cs) => [fresh, ...cs]);
    setActiveId(fresh.id);
  };

  const deleteConversation = (id: string) => {
    if (!confirm("Delete this conversation? This cannot be undone.")) return;
    setConversations((cs) => {
      const remaining = cs.filter((c) => c.id !== id);
      if (remaining.length === 0) return [freshConversation()];
      return remaining;
    });
  };

  const renameConversation = (id: string) => {
    const conv = conversations.find((c) => c.id === id);
    if (!conv) return;
    const next = prompt("New title:", conv.title || "Untitled");
    if (next === null) return;
    setConversations((cs) =>
      cs.map((c) => (c.id === id ? { ...c, title: next.trim() || "Untitled" } : c)),
    );
  };

  const deleteMessage = (msgId: string) => {
    updateActive((c) => ({ ...c, messages: c.messages.filter((m) => m.id !== msgId) }));
  };

  return (
    <div className="h-full flex">
      {/* Conversation sidebar */}
      <aside className="w-56 shrink-0 border-r border-border bg-surface flex flex-col">
        <button
          type="button"
          onClick={newConversation}
          disabled={streaming}
          className="m-2 px-3 py-2 text-xs flex items-center gap-2 bg-accent text-text rounded hover:bg-accent-hover disabled:opacity-50"
        >
          <MessageSquarePlus size={14} />
          New chat
        </button>
        <ul className="flex-1 overflow-y-auto divide-y divide-border">
          {conversations
            .slice()
            .sort((a, b) => b.updatedAt - a.updatedAt)
            .map((c) => (
              <li
                key={c.id}
                className={
                  "px-3 py-2 cursor-pointer group " +
                  (c.id === activeId ? "bg-primary/40" : "hover:bg-primary/20")
                }
                onClick={() => setActiveId(c.id)}
              >
                <div className="flex items-center justify-between gap-1">
                  <div className="text-sm font-medium truncate">
                    {c.title || "Untitled"}
                  </div>
                  <div className="opacity-0 group-hover:opacity-100 flex gap-0.5 shrink-0">
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        renameConversation(c.id);
                      }}
                      className="p-0.5 text-text-dim hover:text-text"
                      title="Rename"
                    >
                      <Pencil size={11} />
                    </button>
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        deleteConversation(c.id);
                      }}
                      className="p-0.5 text-text-dim hover:text-red-300"
                      title="Delete"
                    >
                      <Trash2 size={11} />
                    </button>
                  </div>
                </div>
                <div className="text-[10px] text-text-dim font-mono mt-0.5">
                  {c.messages.length} msg · {new Date(c.updatedAt).toLocaleString()}
                </div>
              </li>
            ))}
        </ul>
      </aside>

      {/* Active conversation */}
      <div className="flex-1 flex flex-col min-w-0">
        <div className="px-6 py-3 border-b border-border bg-surface flex items-center justify-between gap-2">
          <div className="min-w-0">
            <h2 className="text-base font-semibold truncate">
              {active?.title || "Untitled"}
            </h2>
            <div className="text-[10px] text-text-dim font-mono">session: {activeId}</div>
          </div>
          {streaming && (
            <button
              type="button"
              onClick={interrupt}
              className="px-3 py-1 text-xs border border-border rounded text-red-300 hover:bg-red-500/20 shrink-0"
            >
              Interrupt
            </button>
          )}
        </div>

        <div ref={scrollRef} className="flex-1 overflow-y-auto px-6 py-4 space-y-3">
          {(!active || active.messages.length === 0) && (
            <div className="text-text-dim text-sm text-center mt-12">
              Send a message to begin. The assistant streams its response token by token.
            </div>
          )}
          {active?.messages.map((msg) => (
            <MessageBubble
              key={msg.id}
              message={msg}
              onDelete={() => deleteMessage(msg.id)}
            />
          ))}
        </div>

        <Composer
          draft={draft}
          setDraft={setDraft}
          onSend={send}
          disabled={streaming || !active}
        />
      </div>
    </div>
  );
}

function MessageBubble({
  message,
  onDelete,
}: {
  message: ChatMessage;
  onDelete: () => void;
}) {
  const tone =
    message.role === "user"
      ? "bg-primary/40 ml-auto"
      : message.role === "error"
        ? "bg-red-500/20 text-red-200 border border-red-500/40"
        : "bg-surface border border-border";

  const copy = () => {
    navigator.clipboard.writeText(message.content).then(
      () => toast.success("Copied message"),
      () => toast.error("Copy failed"),
    );
  };

  return (
    <div className={`relative max-w-3xl rounded-md px-3 py-2 group ${tone}`}>
      <div className="text-[10px] uppercase tracking-wider opacity-60 mb-1">
        {message.role}
        {message.streaming && <span className="ml-2 animate-pulse">●</span>}
      </div>
      <MessageBody content={message.content} streaming={message.streaming} />
      {!message.streaming && message.content && (
        <div className="absolute -top-2 right-2 hidden group-hover:flex gap-0.5">
          <button
            type="button"
            onClick={copy}
            className="bg-surface border border-border rounded px-1.5 py-0.5 text-text-dim hover:text-text"
            title="Copy"
          >
            <Copy size={11} />
          </button>
          <button
            type="button"
            onClick={onDelete}
            className="bg-surface border border-border rounded px-1.5 py-0.5 text-text-dim hover:text-red-300"
            title="Delete"
          >
            <Trash2 size={11} />
          </button>
        </div>
      )}
    </div>
  );
}

/**
 * Renders message content with ```fenced``` blocks promoted to <pre> with a
 * copy button, and plain text as whitespace-preserving spans.
 */
function MessageBody({
  content,
  streaming,
}: {
  content: string;
  streaming?: boolean;
}) {
  if (!content) {
    return <span className="text-sm">{streaming ? "…" : ""}</span>;
  }
  // Split on ``` markers; alternating segments are non-code / code.
  const parts = content.split(/```([\s\S]*?)```/g);
  return (
    <div className="text-sm leading-relaxed">
      {parts.map((seg, i) => {
        const isCode = i % 2 === 1;
        if (!isCode) {
          return (
            <span key={i} className="whitespace-pre-wrap break-words">
              {seg}
            </span>
          );
        }
        // First line is optional language tag.
        const newline = seg.indexOf("\n");
        const lang = newline > 0 && newline < 20 ? seg.slice(0, newline).trim() : "";
        const body = lang ? seg.slice(newline + 1) : seg;
        return <CodeBlock key={i} lang={lang} body={body} />;
      })}
    </div>
  );
}

function CodeBlock({ lang, body }: { lang: string; body: string }) {
  const copy = () => {
    navigator.clipboard.writeText(body).then(
      () => toast.success("Copied code"),
      () => toast.error("Copy failed"),
    );
  };
  return (
    <div className="relative my-2 group/code">
      {lang && (
        <div className="text-[9px] uppercase tracking-wider text-text-dim px-2 pt-1 font-mono">
          {lang}
        </div>
      )}
      <pre className="bg-bg border border-border rounded px-3 py-2 text-xs font-mono overflow-x-auto whitespace-pre">
        {body}
      </pre>
      <button
        type="button"
        onClick={copy}
        className="absolute top-1.5 right-1.5 bg-surface border border-border rounded px-1.5 py-0.5 text-text-dim hover:text-text opacity-0 group-hover/code:opacity-100"
        title="Copy code"
      >
        <Copy size={11} />
      </button>
    </div>
  );
}

function Composer({
  draft,
  setDraft,
  onSend,
  disabled,
}: {
  draft: string;
  setDraft: (v: string) => void;
  onSend: () => void;
  disabled: boolean;
}) {
  return (
    <form
      className="border-t border-border bg-surface px-6 py-3 flex gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        onSend();
      }}
    >
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            onSend();
          }
        }}
        placeholder="Send a message… (Shift+Enter for newline)"
        rows={2}
        className="flex-1 bg-bg border border-border rounded px-3 py-2 text-sm resize-none"
        disabled={disabled}
      />
      <button
        type="submit"
        disabled={disabled || !draft.trim()}
        className="px-4 py-2 bg-accent text-text rounded text-sm hover:bg-accent-hover disabled:opacity-50 self-end"
      >
        Send
      </button>
    </form>
  );
}
