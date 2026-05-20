import { useEffect, useRef, useState } from "react";

/**
 * Minimal interactive chat tab. Hits POST /api/chat/stream and parses the
 * SSE-style data frames produced by ChatController:
 *
 *   data: {"type":"delta","content":"..."}        // streaming token
 *   data: {"type":"response","content":"..."}     // full final text
 *   data: {"type":"done"}                         // marker
 *   data: {"type":"error","message":"..."}        // failure
 *
 * The protocol does NOT use `text/event-stream`'s "event:" line — every
 * frame is a generic `data:` line, so we parse with fetch() + ReadableStream
 * instead of EventSource (which only supports GET anyway).
 */
interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "error";
  content: string;
  streaming?: boolean;
  timestamp: number;
}

const SESSION_KEY = "kc-chat-session";

export function ChatPage() {
  const [sessionId, setSessionId] = useState<string>(() => {
    const existing = localStorage.getItem(SESSION_KEY);
    if (existing) return existing;
    const fresh = "web-" + Math.random().toString(36).slice(2, 10);
    localStorage.setItem(SESSION_KEY, fresh);
    return fresh;
  });
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages]);

  const send = async () => {
    const text = draft.trim();
    if (!text || streaming) return;
    setDraft("");

    const userMsg: ChatMessage = {
      id: "u-" + Date.now(),
      role: "user",
      content: text,
      timestamp: Date.now(),
    };
    const assistantId = "a-" + Date.now();
    setMessages((m) => [
      ...m,
      userMsg,
      { id: assistantId, role: "assistant", content: "", streaming: true, timestamp: Date.now() },
    ]);
    setStreaming(true);

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const res = await fetch("/api/chat/stream", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "text/event-stream",
          "X-Session-Id": sessionId,
        },
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
          const dataLine = frame
            .split("\n")
            .find((l) => l.startsWith("data:"));
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
            setMessages((m) =>
              m.map((msg) =>
                msg.id === assistantId ? { ...msg, content: finalText } : msg,
              ),
            );
          } else if (parsed.type === "response" && parsed.content) {
            // Final consolidated text — replace whatever deltas built up so far.
            finalText = parsed.content;
            setMessages((m) =>
              m.map((msg) =>
                msg.id === assistantId
                  ? { ...msg, content: finalText, streaming: false }
                  : msg,
              ),
            );
          } else if (parsed.type === "done") {
            setMessages((m) =>
              m.map((msg) =>
                msg.id === assistantId ? { ...msg, streaming: false } : msg,
              ),
            );
          } else if (parsed.type === "error") {
            setMessages((m) =>
              m.map((msg) =>
                msg.id === assistantId
                  ? { ...msg, role: "error", content: parsed.message ?? "error", streaming: false }
                  : msg,
              ),
            );
          }
        }
      }
    } catch (e) {
      if ((e as Error).name !== "AbortError") {
        setMessages((m) =>
          m.map((msg) =>
            msg.id === assistantId
              ? { ...msg, role: "error", content: String((e as Error).message), streaming: false }
              : msg,
          ),
        );
      } else {
        setMessages((m) =>
          m.map((msg) =>
            msg.id === assistantId ? { ...msg, streaming: false } : msg,
          ),
        );
      }
    } finally {
      setStreaming(false);
      abortRef.current = null;
    }
  };

  const interrupt = async () => {
    abortRef.current?.abort();
    try {
      await fetch("/api/chat/interrupt", {
        method: "POST",
        headers: { "X-Session-Id": sessionId },
      });
    } catch {
      // Best-effort — local abort already happened.
    }
  };

  const newSession = () => {
    const fresh = "web-" + Math.random().toString(36).slice(2, 10);
    localStorage.setItem(SESSION_KEY, fresh);
    setSessionId(fresh);
    setMessages([]);
  };

  return (
    <div className="h-full flex flex-col">
      <div className="px-6 py-3 border-b border-border bg-surface flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold">Chat</h2>
          <div className="text-[10px] text-text-dim font-mono">session: {sessionId}</div>
        </div>
        <div className="flex gap-2 text-xs">
          {streaming && (
            <button
              type="button"
              onClick={interrupt}
              className="px-3 py-1 border border-border rounded text-red-300 hover:bg-red-500/20"
            >
              Interrupt
            </button>
          )}
          <button
            type="button"
            onClick={newSession}
            disabled={streaming}
            className="px-3 py-1 border border-border rounded text-text-dim hover:text-text disabled:opacity-50"
          >
            New session
          </button>
        </div>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto px-6 py-4 space-y-3">
        {messages.length === 0 && (
          <div className="text-text-dim text-sm text-center mt-12">
            Send a message to begin. The assistant streams its response token by token.
          </div>
        )}
        {messages.map((msg) => (
          <MessageBubble key={msg.id} message={msg} />
        ))}
      </div>

      <Composer
        draft={draft}
        setDraft={setDraft}
        onSend={send}
        disabled={streaming}
      />
    </div>
  );
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const tone =
    message.role === "user"
      ? "bg-primary/40 ml-auto"
      : message.role === "error"
      ? "bg-red-500/20 text-red-200 border border-red-500/40"
      : "bg-surface border border-border";
  return (
    <div className={`max-w-3xl rounded-md px-3 py-2 ${tone}`}>
      <div className="text-[10px] uppercase tracking-wider opacity-60 mb-1">
        {message.role}
        {message.streaming && <span className="ml-2 animate-pulse">●</span>}
      </div>
      <div className="text-sm whitespace-pre-wrap break-words leading-relaxed">
        {message.content || (message.streaming ? "…" : "")}
      </div>
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
