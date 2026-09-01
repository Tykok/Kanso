import type { View } from "@/store/ui";
import { boardActions } from "./board";
import { coreActions } from "./core";
import { docsActions } from "./docs";
import { favouriteActions } from "./favourites";
import { inboxActions } from "./inbox";
import { organiseActions } from "./organise";
import { publikActions } from "./publik";
import { trashActions } from "./trash";
import type { Action, ActionContext } from "./types";

export type { Action, ActionContext, ActionGroup } from "./types";
export { canPlan, predecessorsOf } from "./core";

/**
 * One registry, composed rather than written.
 *
 * `ACTIONS` used to be a single 467-line array literal, which made it the one expression
 * six branches would all have had to append to at once. Each slice now owns a file and
 * this list names it; the order is fixed and `core` comes first, because
 * [resolveShortcut] answers with the first match and a slice must not be able to take a
 * key core already owns by being loaded earlier. [indexActions] refuses the ambiguity
 * outright at module load, so "first wins" is a tie-break that never runs.
 */
export const ACTIONS: readonly Action[] = [
  ...coreActions,
  ...boardActions,
  ...docsActions,
  ...organiseActions,
  ...inboxActions,
  ...trashActions,
  ...publikActions,
  ...favouriteActions,
];

/** Where a key lives: one bucket per mode, plus `any` for the keys both views share. */
const bucket = (mode: View | undefined, key: string) => `${mode ?? "any"}:${key}`;

/**
 * Indexes a set of actions, refusing a set that cannot be resolved unambiguously.
 * Both throws are load-bearing and both run at module load below: a duplicate id
 * silently loses an action a menu still names, and two actions on one key make a
 * keypress mean whichever was written last.
 *
 * The key check is per mode, not global — `h` on the chart and `h` in the list are two
 * different intents and the whole point of the split — so it is the *bucket* that must
 * be unique, not the key. Exported so the guard can be exercised on a set of its own
 * rather than by breaking the real registry.
 */
export function indexActions(actions: readonly Action[]) {
  const byId = new Map<string, Action>();
  const byKey = new Map<string, Action>();

  for (const action of actions) {
    if (byId.has(action.id)) throw new Error(`Duplicate action id "${action.id}"`);
    byId.set(action.id, action);

    for (const key of action.shortcut?.split(" ") ?? []) {
      const claimed = byKey.get(bucket(action.mode, key));
      if (claimed) {
        throw new Error(`Key "${key}" is claimed by both "${claimed.id}" and "${action.id}"`);
      }
      byKey.set(bucket(action.mode, key), action);
    }
  }

  return { byId, byKey };
}

const { byId: BY_ID, byKey: BY_KEY } = indexActions(ACTIONS);

/**
 * The action a bare keypress means in [mode], before `when` is consulted. The mode's
 * own bucket first, then the shared one, so a view can claim a key without the keys
 * every view answers having to be repeated in each.
 */
export function resolveShortcut(key: string, mode: View): Action | undefined {
  return BY_KEY.get(bucket(mode, key)) ?? BY_KEY.get(bucket(undefined, key));
}

/** Everything currently permitted — what the palette lists and menus filter. */
export function availableActions(ctx: ActionContext): Action[] {
  return ACTIONS.filter((action) => action.when(ctx));
}

/** Throws on an unknown id: a menu referencing a dead action is a bug, not a no-op. */
export function actionById(id: string): Action {
  const action = BY_ID.get(id);
  if (!action) throw new Error(`Unknown action "${id}"`);
  return action;
}

const KEY_LABELS: Record<string, string> = {
  ArrowDown: "↓",
  ArrowUp: "↑",
};

/**
 * The key to print for [action], or nothing when the keyboard cannot reach it.
 *
 * One function for the three surfaces that used to spell this out themselves — the row
 * menus, the command palette and the help overlay — so a display rule cannot hold in one
 * and not the others. [isMac] is passed rather than read here: this module is imported by
 * the test suite under `environment: "node"`, where there is no `navigator` to ask.
 */
export function hintOf(action: Action, isMac: boolean): string | undefined {
  if (action.hint !== undefined) return action.hint.replace("Mod+", isMac ? "⌘" : "Ctrl+");
  // The first spelling only: `ticket.moveDown` owns both `j` and `ArrowDown`, and a menu
  // entry reading "j ArrowDown" teaches nothing.
  const first = action.shortcut?.split(" ")[0];
  return first === undefined ? undefined : (KEY_LABELS[first] ?? first);
}

/**
 * Rows for the help overlay, generated from the shortcuts.
 *
 * Each row carries its mode — undefined for the keys both views answer — because a
 * flat list would offer `h` `l` `H` `L` to someone in the list, where they do nothing
 * at all.
 *
 * An action carrying only a `hint` gets a row too: that is what replaced the hardcoded
 * `⌘K` pair the overlay used to draw beneath the generated list.
 */
export function shortcutRows(
  isMac: boolean,
): { mode: View | undefined; keys: string; label: string }[] {
  return ACTIONS.flatMap((action) => {
    const keys =
      action.hint !== undefined
        ? hintOf(action, isMac)
        : action.shortcut
            ?.split(" ")
            .map((key) => KEY_LABELS[key] ?? key)
            .join(" / ");
    return keys === undefined ? [] : [{ mode: action.mode, keys, label: action.label }];
  });
}
