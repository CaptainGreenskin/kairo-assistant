import { useEffect, useState } from "react";
import {
  type SlotName,
  pluginSlotRegistry,
  usePluginSlotRegistry,
} from "../lib/plugins/pluginSlots";

/**
 * Renders every plugin contribution registered for {@code name}, in
 * registration order. Re-renders on registry mutations.
 *
 * Pages that want extensibility points just drop:
 *
 *   <PluginSlot name="dashboard.cards" />
 *
 * anywhere in their JSX. The list is empty until a plugin registers, so
 * there's zero visual cost when no plugin uses the slot.
 */
export function PluginSlot({ name }: { name: SlotName }) {
  const registry = usePluginSlotRegistry();
  const [version, setVersion] = useState(0);

  useEffect(() => {
    return registry.subscribe(() => setVersion((v) => v + 1));
  }, [registry]);

  const items = pluginSlotRegistry.forSlot(name);
  if (items.length === 0) return null;

  return (
    <>
      {items.map((c) => {
        const C = c.component;
        return <C key={`${c.slot}:${c.id}:${version}`} />;
      })}
    </>
  );
}
