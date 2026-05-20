import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { createElement } from "react";
import { useQueryClient } from "@tanstack/react-query";

/**
 * Subscribe to the assistant server's KairoEventBus over SSE and trigger
 * React-Query invalidations when relevant domains fire. Mirrors the
 * hermes-hudui pattern: server pushes a lightweight "data changed" notice,
 * client refetches via REST.
 *
 * Wire protocol:
 *   GET /api/events/stream                       — all events
 *   GET /api/events/stream?domain=cron           — cron domain only
 *   GET /api/events/stream?domain=cron&domain=evolution
 *
 * Each event arrives as `event: kairo\ndata: <json>` with payload shape
 *   { eventId, timestamp, domain, eventType, attributes }
 */
interface KairoSseEvent {
  eventId: string;
  timestamp: string;
  domain: string;
  eventType: string;
  attributes: Record<string, unknown>;
}

export type SseStatus = "connecting" | "live" | "offline";

const STREAM_URL = "/api/events/stream?domain=cron&domain=evolution";

const INVALIDATE_MAP: Record<string, ReadonlyArray<readonly unknown[]>> = {
  cron: [["cron", "tasks"]],
  evolution: [["evolution", "skills"]],
};

const SseStatusContext = createContext<SseStatus>("connecting");

export const useSseStatus = () => useContext(SseStatusContext);

/**
 * Mount one EventSource per app, expose connection status via context.
 * The browser keeps the underlying HTTP connection alive until the tab
 * closes; SSE also reconnects automatically on transient network drops.
 */
export function EventStreamProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const [status, setStatus] = useState<SseStatus>("connecting");

  useEffect(() => {
    const source = new EventSource(STREAM_URL);
    setStatus("connecting");

    source.onopen = () => setStatus("live");
    source.onerror = () => {
      // EventSource auto-reconnects; readyState transitions tell us where we are.
      setStatus(source.readyState === EventSource.CLOSED ? "offline" : "connecting");
    };

    const onKairo = (ev: MessageEvent) => {
      try {
        const payload = JSON.parse(ev.data) as KairoSseEvent;
        const queries = INVALIDATE_MAP[payload.domain];
        if (queries) {
          for (const queryKey of queries) {
            qc.invalidateQueries({ queryKey: queryKey as readonly unknown[] });
          }
        }
      } catch (e) {
        console.warn("Failed to parse SSE event", e);
      }
    };
    source.addEventListener("kairo", onKairo as EventListener);

    return () => {
      source.removeEventListener("kairo", onKairo as EventListener);
      source.close();
    };
  }, [qc]);

  return createElement(SseStatusContext.Provider, { value: status }, children);
}
