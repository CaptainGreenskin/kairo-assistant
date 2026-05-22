import { useQuery } from "@tanstack/react-query";
import { Sparkles } from "lucide-react";
import { registerPluginSlot } from "../lib/plugins/pluginSlots";
import { analyticsApi } from "../api/console";

/**
 * Reference example for in-bundle plugins. Demonstrates the contribution
 * pattern — registers a small "today's cost" widget into the dashboard.
 *
 * Plugins call `registerPluginSlot()` once at module-load time; this file
 * is imported from main.tsx so the registration happens before the React
 * tree mounts.
 */
function TodayCostCard() {
  const tokens = useQuery({
    queryKey: ["analytics", "tokens"],
    queryFn: analyticsApi.tokens,
    refetchInterval: 30_000,
  });
  return (
    <div className="border border-border rounded p-3 bg-surface">
      <div className="text-xs uppercase tracking-wider text-text-dim mb-1 flex items-center gap-1.5">
        <Sparkles size={11} />
        Plugin example · today cost
      </div>
      <div className="text-lg font-semibold tabular-nums text-accent">
        ${tokens.data ? tokens.data.estimatedCostUsd.toFixed(4) : "—"}
      </div>
    </div>
  );
}

registerPluginSlot({
  id: "example.today-cost",
  slot: "dashboard.cards",
  component: TodayCostCard,
});
