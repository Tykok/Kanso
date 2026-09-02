"use client";

import { actionById, permits, type ActionContext } from "@/lib/actions";
import { isMac } from "@/lib/platform";
import { hintFor, type Bindings } from "@/lib/shortcuts";
import { useBindings } from "@/lib/use-bindings";
import type { MenuItem } from "./menu";

/**
 * Actions that destroy something. They are set apart in every menu that lists them —
 * the rule lives here rather than in each caller, so a menu cannot forget it.
 */
export const DESTRUCTIVE: ReadonlySet<string> = new Set([
  "team.delete",
  "project.delete",
  "ticket.delete",
]);

/**
 * Turns action ids into menu items, dropping the ones this context does not permit.
 * `actionById` throws on an unknown id: a menu naming a dead action is a bug worth a
 * crash at startup, not an item that silently never appears.
 *
 * A hook, since §6.3, and that is the whole of the change: the key each item prints comes
 * from the reader's own bindings rather than from the registry's defaults, and the
 * bindings are behind `usePreferences()`. Six callers, all of them component render
 * bodies. The alternative was to keep this a plain function and hand it the bindings at
 * every call site, which is the same six edits plus a parameter that could be forgotten
 * at the seventh.
 */
export function useMenuItems(ctx: ActionContext | undefined, ids: string[]): MenuItem[] {
  const { keys } = useBindings();
  return ctx ? menuItems(ctx, ids, keys) : [];
}

/**
 * The same, given the bindings. Exported for the tests, which have no React to run a hook
 * in — and it is the pure half anyway, which is where the rule about `hint` lives.
 */
export function menuItems(ctx: ActionContext, ids: string[], keys: Bindings): MenuItem[] {
  const mac = isMac();
  return ids
    .map((id) => actionById(id))
    .filter((action) => permits(action, ctx))
    .map((action) => ({
      id: action.id,
      label: action.label,
      // One rule for every surface that prints a key — the `?` sheet, the status bar and
      // every menu — and one that reads the effective binding, so a remapped key is
      // remapped here too. `hintFor` prints the first chord only: `ticket.moveDown`
      // answers both `n` and `↓`, and a menu entry reading "n ↓" teaches nothing.
      hint: hintFor(action, keys, mac),
      danger: DESTRUCTIVE.has(action.id),
      onSelect: () => action.run(ctx),
    }));
}
