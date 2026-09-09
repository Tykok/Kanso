"use client";

import { actionById, permits, type ActionContext } from "@/lib/actions";
import { optionsFor } from "@/lib/statuses";
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

/**
 * How many statuses the digits reach — `ticket.status.1` … `ticket.status.6` in `core.ts`.
 *
 * Six because Kanso seeds six, not because six is a limit on a team: the seventh word is
 * reachable through this menu and through `⇧s`, and prints no key because it has none.
 */
const DIGIT_STATUSES = 6;

/**
 * The status menu on a pill, built from a vocabulary rather than from action ids.
 *
 * Every other menu in this app is a list of ids, and this one cannot be — `KAN-92` made
 * the digits positional, so `ticket.status.3`'s label is "the team's 3rd" and a menu of
 * those would name no status at all. The words live on the ticket's own team, so the rows
 * come from `statuses.optionsFor` and the registry is asked for one thing only: which key
 * reaches the action at that position, so the menu is where the pairing is learnt.
 *
 * Empty with no selection and empty for a seat that cannot write, which is `permits`'
 * answer for the actions it stands in for — a menu of things that will all fail is worse
 * than no menu, and these all write.
 */
export function statusMenuItems(ctx: ActionContext, keys: Bindings): MenuItem[] {
  const ticket = ctx.selected;
  if (!ticket || !ctx.canWrite) return [];
  const mac = isMac();
  return optionsFor(ctx.teams, ticket.teamId).map((status, at) => ({
    id: status.key,
    // The same sentence the registry's own labels are built from, and the same one the
    // priority menu beside it prints — what changes is where the word comes from, which
    // is the ticket's team rather than a table of six.
    label: `Set status: ${status.label}`,
    // `at + 1`, because the keys a reader presses are one-based — and `undefined` past
    // the sixth, where the digits stop and the catalogue does not. Guarded rather than
    // caught: `actionById` throws on an id the registry has never had, which is the right
    // answer for a menu naming a dead action and the wrong one for a team's seventh word.
    hint:
      at < DIGIT_STATUSES ? hintFor(actionById(`ticket.status.${at + 1}`), keys, mac) : undefined,
    onSelect: () => {
      ctx.patchTicket({ id: ticket.id, status: status.key });
      ctx.close();
    },
  }));
}

/** [statusMenuItems] against the reader's own bindings — the hook every pill calls. */
export function useStatusMenu(ctx: ActionContext | undefined): MenuItem[] {
  const { keys } = useBindings();
  return ctx ? statusMenuItems(ctx, keys) : [];
}
