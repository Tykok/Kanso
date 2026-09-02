import { boardActions } from "./board";
import { canonicalChord, formatChord } from "./chords";
import { coreActions } from "./core";
import { docsActions } from "./docs";
import { favouriteActions } from "./favourites";
import { inboxActions } from "./inbox";
import { organiseActions } from "./organise";
import { publikActions } from "./publik";
import { trashActions } from "./trash";
import type { Action, ActionContext, ShortcutMode } from "./types";

export type { Action, ActionContext, ActionGroup, ShortcutMode } from "./types";
export { canPlan, predecessorsOf, PRIORITY_ACTIONS } from "./core";
export { claim, claimed, clearClaims, runClaim } from "./claims";
export {
  canonicalChord,
  chordOf,
  formatChord,
  parseChord,
  type Chord,
  type ChordEvent,
  type ParsedChord,
} from "./chords";

/**
 * One registry, composed rather than written.
 *
 * `ACTIONS` used to be a single 467-line array literal, which made it the one expression
 * six branches would all have had to append to at once. Each slice now owns a file and
 * this list names it; the order is fixed and `core` comes first, because it is also the
 * order `mergeBindings` lays the defaults down in and a slice must not be able to take a
 * key core already owns by being loaded earlier. [indexActions] refuses the ambiguity
 * outright at module load, so "first wins" is a tie-break that never runs *for the
 * defaults* — and for a reader's overrides it is what decides which of two colliding
 * claims is the one refused.
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

/**
 * Where a chord lives: one bucket per mode, plus `any` for the chords every screen shares.
 *
 * Exported because `lib/shortcuts.ts` builds the same buckets out of the reader's own
 * bindings, and two spellings of one key would be a bug that only shows up on a remapped
 * keyboard — the hardest kind to be told about.
 */
export const bucketOf = (mode: ShortcutMode | undefined, chord: string) =>
  `${mode ?? "any"}:${chord}`;

/**
 * Indexes a set of actions, refusing a set that cannot be resolved unambiguously.
 * All three throws are load-bearing and all three run at module load below: a duplicate id
 * silently loses an action a menu still names, two actions on one chord make a keypress
 * mean whichever was written last, and a chord that is not a chord is a key nobody can
 * ever press.
 *
 * The chord check is per mode, not global — `h` on the chart and `h` in the list are two
 * different intents and the whole point of the split — so it is the *bucket* that must be
 * unique, not the chord. Exported so the guard can be exercised on a set of its own rather
 * than by breaking the real registry.
 *
 * This stays a throw where `mergeBindings` refuses and reports: a developer's typo should
 * stop the build, and a reader's stored preference must never be able to stop the app.
 */
export function indexActions(actions: readonly Action[]) {
  const byId = new Map<string, Action>();
  const byKey = new Map<string, Action>();

  for (const action of actions) {
    if (byId.has(action.id)) throw new Error(`Duplicate action id "${action.id}"`);
    byId.set(action.id, action);

    for (const chord of action.defaultKeys ?? []) {
      if (canonicalChord(chord) !== chord) {
        throw new Error(`"${chord}" is not a chord in canonical spelling ("${action.id}")`);
      }
      const claimed = byKey.get(bucketOf(action.mode, chord));
      if (claimed) {
        throw new Error(`Key "${chord}" is claimed by both "${claimed.id}" and "${action.id}"`);
      }
      byKey.set(bucketOf(action.mode, chord), action);
    }
  }

  return { byId, byKey };
}

const { byId: BY_ID } = indexActions(ACTIONS);

/**
 * Whether [action] is offered at all, right now.
 *
 * The one place `when` is consulted, and the one place the read-only seat is applied to
 * the registry — every surface goes through it: the palette, the row and sidebar menus,
 * and the keyboard. A per-action `when: ctx.canWrite && …` would have been thirty-five
 * chances to forget the thirty-sixth, which is the same argument `ReadOnlySeat` makes
 * against a `@PreAuthorize` per controller.
 *
 * The seat is asked *before* `when`, so a writing action is not merely hidden but
 * unreachable — a shortcut that resolved to it would still be refused here.
 */
export function permits(action: Action, ctx: ActionContext): boolean {
  if (action.writes && !ctx.canWrite) return false;
  return action.when(ctx);
}

/** Everything currently permitted — what the palette lists and menus filter. */
export function availableActions(ctx: ActionContext): Action[] {
  return ACTIONS.filter((action) => permits(action, ctx));
}

/** Throws on an unknown id: a menu referencing a dead action is a bug, not a no-op. */
export function actionById(id: string): Action {
  const action = BY_ID.get(id);
  if (!action) throw new Error(`Unknown action "${id}"`);
  return action;
}

/** Whether [id] is an action at all. What `mergeBindings` asks of a stored override. */
export function isActionId(id: string): boolean {
  return BY_ID.has(id);
}

/**
 * The **default** key to print for [action], for the one surface that cannot yet ask for
 * the reader's own.
 *
 * Every other surface reads the effective bindings — `hintFor` in `lib/shortcuts.ts`, fed
 * by `useBindings()`, which is what makes the help sheet, the row menus and the status bar
 * agree with the dispatcher whatever the reader has remapped. The command palette's rows
 * are assembled in `components/shell/overlays.tsx`, which this slice does not own; until
 * that one call site moves to `hintFor`, it prints the default, and on a remapped keyboard
 * the palette is the only place in the interface that can be out of date.
 *
 * [isMac] is passed rather than read here: this module is imported by the test suite under
 * `environment: "node"`, where there is no `navigator` to ask.
 */
export function hintOf(action: Action, isMac: boolean): string | undefined {
  // The first spelling only: `ticket.moveDown` owns both `n` and `ArrowDown`, and a menu
  // entry reading "n ↓" teaches nothing.
  const first = action.defaultKeys?.[0];
  return first === undefined ? undefined : formatChord(first, isMac);
}
