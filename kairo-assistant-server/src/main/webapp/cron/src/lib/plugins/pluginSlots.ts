import { createContext, useContext, type ComponentType } from "react";

/**
 * Minimal plugin-slot pattern, borrowed from hermes-agent/web.
 *
 * Concept: pages can declare named "slots" where future plugins can inject
 * UI. Plugins register a {slot, component} pair via `registerPluginSlot()`;
 * pages render `<PluginSlot name="…" />` which iterates anything registered
 * for that slot.
 *
 * Why a registry-of-components instead of dynamic imports? Because today
 * all plugins ship statically in the same bundle (built-in to the console).
 * Hot-loading remote plugin bundles is a bigger architectural shift — we
 * leave a hook here for it but don't pay the cost yet.
 *
 * Current slots that pages render:
 *   "dashboard.cards"     → extra cards on the Dashboard page
 *   "evolution.actions"   → extra buttons on Evolution toolbar
 *   "chat.composer.extra" → extra widgets next to the chat Send button
 *   "sidebar.extra"       → extra nav links at the bottom of the sidebar
 *
 * Adding a slot is just adding `<PluginSlot name="my.slot" />` somewhere
 * and documenting it here.
 */
export type SlotName =
  | "dashboard.cards"
  | "evolution.actions"
  | "chat.composer.extra"
  | "sidebar.extra";

export interface SlotContribution {
  id: string;
  slot: SlotName;
  component: ComponentType;
}

class PluginSlotRegistry {
  private contributions: SlotContribution[] = [];
  private listeners = new Set<() => void>();

  register(c: SlotContribution) {
    if (this.contributions.some((x) => x.id === c.id && x.slot === c.slot)) {
      console.warn(`PluginSlot: duplicate registration ${c.id} for ${c.slot}`);
      return () => {};
    }
    this.contributions.push(c);
    this.notify();
    return () => this.unregister(c.id, c.slot);
  }

  unregister(id: string, slot: SlotName) {
    const before = this.contributions.length;
    this.contributions = this.contributions.filter(
      (c) => !(c.id === id && c.slot === slot),
    );
    if (before !== this.contributions.length) this.notify();
  }

  forSlot(slot: SlotName): SlotContribution[] {
    return this.contributions.filter((c) => c.slot === slot);
  }

  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => {
      this.listeners.delete(fn);
    };
  }

  private notify() {
    for (const fn of this.listeners) fn();
  }
}

export const pluginSlotRegistry = new PluginSlotRegistry();

export function registerPluginSlot(c: SlotContribution): () => void {
  return pluginSlotRegistry.register(c);
}

// Re-export the React hook contract for consumers.
export const PluginSlotContext = createContext(pluginSlotRegistry);
export const usePluginSlotRegistry = () => useContext(PluginSlotContext);
