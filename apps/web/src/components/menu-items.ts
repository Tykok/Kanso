import { actionById, type ActionContext } from "@/lib/actions";
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
 */
export function menuItems(ctx: ActionContext, ids: string[]): MenuItem[] {
  return ids
    .map((id) => actionById(id))
    .filter((action) => action.when(ctx))
    .map((action) => ({
      id: action.id,
      label: action.label,
      danger: DESTRUCTIVE.has(action.id),
      onSelect: () => action.run(ctx),
    }));
}
