import {
  ACTIONS,
  bucketOf,
  canonicalChord,
  formatChord,
  parseChord,
  type Action,
  type ActionGroup,
  type ShortcutMode,
} from "@/lib/actions";
import { chordsFor, type BindingIndex, type Bindings } from "@/lib/shortcuts";
import { SHORTCUT_SECTIONS } from "../shortcut-sections";

/**
 * The keyboard as a table, and the four rules that decide what a reader may put in it.
 *
 * Out here rather than inside `shortcuts-section.tsx` for the reason `burndown.ts` and
 * `reveal.ts` are modules: none of this needs a DOM to be true. The refusal a capture
 * prints is the same claim `mergeBindings` makes over stored data, and a rule stated twice
 * in two languages — once in a merge, once in a click handler — is a rule that will
 * eventually disagree with itself. So the section renders and this decides.
 *
 * The section is the only caller. It is not a component, so the unit suite can ask it
 * every question about the refusals under `environment: "node"`.
 */

/** At most eight chords on one action — `PreferencesService.MAX_CHORDS`, restated. */
export const MAX_CHORDS_PER_ACTION = 8;

/**
 * The headings above the groups, in the order `ACTIONS` composes them.
 *
 * `ActionGroup` is five words in the singular (`"ticket"`, `"app"`) because it labels one
 * action; a heading labels a run of them, so it is plural here and nowhere else. The
 * order is fixed rather than derived from the registry: `ACTIONS` puts `app` in the
 * middle, which is right for a palette listing recency and wrong for a table somebody
 * reads top to bottom.
 */
export const GROUP_TITLES: readonly { group: ActionGroup; title: string }[] = [
  { group: "ticket", title: "Tickets" },
  { group: "view", title: "Views and filters" },
  { group: "team", title: "Teams" },
  { group: "project", title: "Projects" },
  { group: "app", title: "The application" },
];

/**
 * What the mode column says, borrowed from the `?` sheet rather than written again.
 *
 * The two surfaces answer the same question — "where does this key work" — and a reader
 * who meets "On the board" in the help sheet and "board" here has to work out that they
 * are the same place. `shortcut-sections.test.ts` already guarantees the list is complete
 * over the registry, so this lookup cannot come up empty for a mode an action declares.
 */
export function modeTitle(mode: ShortcutMode | undefined): string {
  return SHORTCUT_SECTIONS.find((section) => section.mode === mode)?.title ?? "Anywhere";
}

export type ShortcutRow = {
  action: Action;
  /** The chords that reach it today — effective, never `defaultKeys`. */
  chords: readonly string[];
  /** Whether the reader has said anything about this action at all. */
  overridden: boolean;
  /** What the mode column prints. */
  where: string;
  /** The chords as a person reads them, which is also what the search box searches. */
  printed: readonly string[];
};

export type ShortcutGroup = { group: ActionGroup; title: string; rows: readonly ShortcutRow[] };

/**
 * Every action in the registry, grouped, with the keys it currently answers.
 *
 * **Every** action, including the twenty-one that ship with no key at all — the five
 * priorities behind `⇧p`'s picker, `ticket.delete`, `timeline.schedule`, and the team and
 * project commands the palette owns. That is decision one of §6.5's three, and it goes
 * this way because the alternative is worse in a way this whole slice is about: bindings
 * became data precisely so that which key reaches an intention stops being a developer's
 * call, and a table that draws only the actions a developer already chose a key for keeps
 * exactly that call. `mergeBindings` accepts any id it knows, the storage holds eight
 * chords for any of them, and the dispatcher resolves whatever is in the index — so the
 * capability exists in the data whether or not the table admits it, and hiding it would
 * only mean the reader who wants `Delete` on a key cannot have it while a hand-written
 * preference row could.
 *
 * The cost is a long table, and the search box above it is the answer the spec already
 * gives to that. The one thing that must not be quiet is what a *reset* means here: for
 * these rows the registry's answer is "no key", so resetting them unbinds rather than
 * restoring something, and the row says `Not bound` rather than leaving a cell empty that
 * would read as a bug.
 */
export function shortcutTable(keys: Bindings, overrides: Bindings, isMac: boolean): ShortcutGroup[] {
  return GROUP_TITLES.map(({ group, title }) => ({
    group,
    title,
    rows: ACTIONS.filter((action) => action.group === group).map((action) => {
      const chords = chordsFor(action.id, keys);
      return {
        action,
        chords,
        overridden: action.id in overrides,
        where: modeTitle(action.mode),
        printed: chords.map((chord) => formatChord(chord, isMac)),
      };
    }),
  }));
}

/**
 * Whether [row] answers [query] — over its label *and* its keys, as §6.5 asks.
 *
 * Keys are matched in both spellings: the canonical one somebody who has read the spec
 * would type (`Mod+f`, `Shift+ArrowDown`) and the printed one they can actually see on the
 * row (`⌘F`, `⇧↓`). Searching only the printed form would make the table unsearchable for
 * `mod`; searching only the canonical form would make it unsearchable by the thing on
 * screen. Case-insensitive on both, because `⌘F` prints upper and is stored lower.
 */
export function matches(row: ShortcutRow, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (needle === "") return true;
  const haystack = [row.action.label, row.where, ...row.chords, ...row.printed];
  return haystack.some((text) => text.toLowerCase().includes(needle));
}

/**
 * Why [chord] cannot go on [action], or `undefined` if it can.
 *
 * The wording of the conflict is `mergeBindings`' own, deliberately: the merge says
 * `already held by "Move down"` about a stored binding it refused, and a reader who meets
 * that sentence at the moment of capture and again in the standing-refusals strip should
 * not have to work out that the two are the same objection.
 *
 * The conflict is per **bucket**, not per chord, which is the same latitude the merge
 * takes and the feature §6.5 names: `x` archives a ticket everywhere and selects a row on
 * a saved view, and cross-mode shadowing is why `mode` exists at all. A chord free in this
 * action's own bucket is therefore free even when another screen answers it.
 */
export function refuseChord(
  chord: string,
  action: Action,
  keys: Bindings,
  index: BindingIndex,
): string | undefined {
  const canonical = canonicalChord(chord);
  if (canonical === undefined) return `"${chord}" is not a key combination`;

  // The two keys by which a reader escapes a capture that went wrong, refused whatever
  // modifiers are held: `⇧⇥` walks focus backwards as surely as `⇥` walks it forward, and
  // `Escape` is in the dispatcher's back-set unconditionally, so binding it elsewhere
  // would print a promise the keyboard does not keep.
  const key = parseChord(canonical)?.key;
  if (key === "Escape" || key === "Tab") {
    return "Escape and Tab are how you get out of a capture, so neither can be assigned";
  }

  const current = chordsFor(action.id, keys);
  if (current.includes(canonical)) return "already reaches this action";

  const holder = index.get(bucketOf(action.mode, canonical));
  if (holder && holder.id !== action.id) return `already held by "${holder.label}"`;

  if (current.length >= MAX_CHORDS_PER_ACTION) {
    return `one action holds at most ${MAX_CHORDS_PER_ACTION} keys`;
  }
  return undefined;
}

/** Whether a keydown is a modifier being *held* rather than a key being pressed. */
export function isModifierKey(key: string): boolean {
  return key === "Shift" || key === "Control" || key === "Alt" || key === "Meta";
}

/**
 * Whether this chord is drawn without a remove button, because removing it would do
 * nothing.
 *
 * One case, and it is the only key in the app that cannot be taken away: `use-shell-keys`
 * builds its back-set as `["Escape", ...chordsFor("app.back", keys)]`, so `Escape` closes
 * what is open and leaves whatever storage says. A `×` beside it would write a preference,
 * repaint the row and change no keystroke at all — a control whose only honest behaviour
 * is to do nothing, which is the mistake this pass removed from the top bar.
 *
 * The row still takes *more* chords, and `app.back` can still be reset, because those two
 * gestures both mean something. Only the one that would lie is absent.
 */
export function isLockedChord(actionId: string, chord: string): boolean {
  return actionId === "app.back" && chord === "Escape";
}

/**
 * The next `preferences.shortcuts` with [chord] added to [id].
 *
 * **Added, not substituted** — decision two of §6.5's three. `mergeBindings` replaces an
 * action's chords wholesale, so an override *is* that action's complete list and the write
 * has to spell out the list; what is decided here is whether capturing a key means "and
 * also this" or "instead of everything". It means "and also", because `ticket.moveDown`
 * ships as `n` and `↓` and those two are one intention spelled twice: a reader adding `j`
 * to it with replace semantics would silently lose the arrow, and — worse — could not get
 * it back, since after the write nothing on screen or in storage still says the arrow was
 * ever there. Each chord gets its own remove instead, which is the same gesture in two
 * clicks and never destroys a spelling nobody mentioned.
 *
 * The list written is the **effective** one, so a chord the merge refused on load is not
 * carried forward: it never reached the keyboard, the reader has just been shown why, and
 * re-storing it would keep a standing refusal alive across every later edit.
 *
 * Only the touched action gets an entry. This is not the "full copy of the defaults" §6.4
 * forbids — that is a map naming every action, which would freeze one release's keyboard
 * into an account; this names the one action the reader changed, and every other default
 * still comes from the registry and still improves with it.
 */
export function withChord(
  overrides: Bindings,
  keys: Bindings,
  id: string,
  chord: string,
): Record<string, string[]> {
  const canonical = canonicalChord(chord) ?? chord;
  return { ...plain(overrides), [id]: [...chordsFor(id, keys), canonical] };
}

/**
 * The next map with [chord] gone from [id].
 *
 * An empty list is a legitimate value and the only way to say "no key at all": the merge
 * treats a mentioned id as remapped and lays none of its defaults down, so `[]` unbinds.
 * That is how the one chord §11 flags as a gamble — `Mod+v`, which shadows paste — becomes
 * one click rather than a support thread.
 */
export function withoutChord(
  overrides: Bindings,
  keys: Bindings,
  id: string,
  chord: string,
): Record<string, string[]> {
  const kept = chordsFor(id, keys).filter((held) => held !== chord);
  return { ...plain(overrides), [id]: [...kept] };
}

/** The next map with [id] handed back to the registry — the row's `Reset`. */
export function withoutOverride(overrides: Bindings, id: string): Record<string, string[]> {
  const next = plain(overrides);
  delete next[id];
  return next;
}

/**
 * A mutable copy, because `Bindings` is `Readonly` and a write needs a plain object.
 *
 * The read-only type is not decoration: `DEFAULT_PREFERENCES.shortcuts` is one frozen `{}`
 * shared by reference with every spread of it, so an override has to be a new object and
 * never a write into the one that came out of the cache.
 */
function plain(overrides: Bindings): Record<string, string[]> {
  return Object.fromEntries(Object.entries(overrides).map(([id, chords]) => [id, [...chords]]));
}
