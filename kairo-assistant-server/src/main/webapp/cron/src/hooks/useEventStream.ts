import { useEffect } from "react";
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

const STREAM_URL = "/api/events/stream?domain=cron&domain=evolution";

const INVALIDATE_MAP: Record<string, ReadonlyArray<readonly unknown[]>> = {
  cron: [["cron", "tasks"]],
  evolution: [["evolution", "skills"]],
};

/**
 * Mount one EventSource per page. The browser keeps the underlying HTTP
 * connection alive until the tab closes or the user navigates away; SSE
 * also reconnects automatically on transient network drops.
 */
export function useEventStream() {
  const qc = useQueryClient();
  useEffect(() => {
    const source = new EventSource(STREAM_URL);
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
    source.onerror = () => {
      // Browser will auto-reconnect — no manual handling needed for transient
      // errors. We log so persistent failures show up in DevTools.
      console.debug("SSE connection hiccup; browser will retry");
    };
    return () => {
      source.removeEventListener("kairo", onKairo as EventListener);
      source.close();
    };
  }, [qc]);
}
