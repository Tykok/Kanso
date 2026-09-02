import {
  ACTIONS,
  bucketOf,
  canonicalChord,
  formatChord,
  isActionId,
  type Action,
  type ShortcutMode,
} from "./actions";

/**
 * Bindings as data: the defaults derived from the registry, the reader's overrides merged
 * onto them, and the one function that turns a chord into text.
 *
 * The registry owns *what* an action is and *when* it may run. This owns *which key
 * reaches it*, and the split is the whole point of §6.3: a key that lives in the registry
 * is a key nobody can change, and the previous arrangement had the printed key and the
 * dispatched key in two fields that could — and did — disagree.
 *
 * Everything here is pure and tested. The React half is `lib/use-bindings.ts`, which is
 * three lines: read `preferences.shortcuts`, call [mergeBindings], memoise.
 *
 * `preferences.shortcuts` holds **overrides only**, never a copy of the defaults. A stored
 * copy would freeze one release's key set into every existing account and a default
 * improved later would reach nobody; `{}` is the honest representation of "I never changed
 * anything", and it is the common case. `lib/theme.ts` already shape-guards the value at
 * bootstrap so the first paint has something to go on, and the server validates shape and
 * nothing else — it cannot know whether `ticket.rename` exists, because the registry is a
 * module in this bundle. Meaning is decided here.
 *
 * The chord grammar itself lives in `lib/actions/chords.ts` and is re-exported below, so
 * §6.5 has one module to import. It is a separate file for a mechanical reason its own
 * docstring gives: [DEFAULT_BINDINGS] is computed at module load from `ACTIONS`, and the
 * registry has to print a key, so a grammar living here would close a cycle that ends in a
 * temporal-dead-zone throw.
 */
export {
  canonicalChord,
  chordOf,
  formatChord,
  parseChord,
  type Chord,
  type ChordEvent,
  type ParsedChord,
} from "./actions";

/** Action id to the chords that reach it. The shape `preferences.shortcuts` overrides. */
export type Bindings = Readonly<Record<string, readonly string[]>>;

/**
 * `"list:n"`, `"any:?"`, `"triage:x"` — a bucket to the action that holds it.
 *
 * Built from the effective bindings rather than from the registry, which is what makes a
 * remapped key dispatch. [resolveShortcut] is the only reader.
 */
export type BindingIndex = ReadonlyMap<string, Action>;

/**
 * A stored binding that was not applied, and why. What §6.5's settings table shows.
 *
 * [heldBy] names the action that already holds the chord, when that is the reason —
 * "never a silent steal" is the rule the capture UI enforces at the moment of capture, and
 * this is the same rule applied to data that arrived some other way: an older release, a
 * hand-edited row, an id renamed underneath a stored preference.
 */
export type RejectedBinding = {
  actionId: string;
  chord: string;
  reason: string;
  heldBy?: string;
};

export type MergedBindings = {
  /** Every action that has a key, and the chords it answers, in printing order. */
  keys: Bindings;
  index: BindingIndex;
  rejected: readonly RejectedBinding[];
};

/**
 * The keyboard as it ships, read off the registry so the defaults have one source.
 *
 * Not stored, not copied, not written down twice. `Action.defaultKeys` is where a slice
 * declares its keys and this is the only derivation of them, which is why improving a
 * default reaches every account that has not deliberately changed it.
 */
export const DEFAULT_BINDINGS: Bindings = Object.freeze(
  Object.fromEntries(
    ACTIONS.flatMap((action) =>
      action.defaultKeys && action.defaultKeys.length > 0
        ? [[action.id, Object.freeze([...action.defaultKeys])] as const]
        : [],
    ),
  ),
);

/**
 * The reader's keyboard: the defaults, with their overrides laid on top, and a list of
 * whatever could not be applied.
 *
 * **Refused, never thrown.** `indexActions` crashes the app at module load over a
 * developer's typo, and that is right — a build that ships an ambiguous key is a build
 * nobody should run. This is the opposite case: the input is user data, it outlives
 * deploys, and a preference that bricks the interface is unacceptable. So every failure
 * comes back in [MergedBindings.rejected] with a sentence, and the rest of the keyboard
 * works.
 *
 * Two passes, and the order is the rule:
 *
 *  1. Lay down the default chords of every action the overrides do **not** mention. An
 *     action that has been remapped gives its default key up — otherwise remapping would
 *     be impossible without also clearing whoever else wanted the key.
 *  2. Lay down each override, in registry order. A chord whose bucket is already held is
 *     refused, naming its holder.
 *
 * The alternative — letting an override steal a chord from a default — is what §6.5
 * forbids in as many words: "Never a silent steal — the registry throws over exactly this
 * ambiguity today, and the UI must be as strict without being fatal." The reader is told
 * who holds the key and can free it first, which is one more click and no lost keystrokes.
 *
 * **Unknown ids are ignored**, silently and on purpose: an action deleted in a later
 * version must not make a stored preference unreadable, and there is nothing for the
 * reader to do about it. A chord that is not a chord *is* reported, because that one is
 * about a key they chose.
 */
export function mergeBindings(overrides?: Record<string, readonly string[]>): MergedBindings {
  const rejected: RejectedBinding[] = [];
  const index = new Map<string, Action>();
  const keys: Record<string, readonly string[]> = {};

  // Unknown ids drop out here rather than being reported: see the docstring. An id this
  // build has never heard of is not a mistake the reader made, and there is nothing they
  // could do about it if they were told.
  const remapped = new Set(Object.keys(overrides ?? {}).filter(isActionId));

  /** One chord onto one action, or why it could not go. */
  const lay = (action: Action, chord: string): Omit<RejectedBinding, "actionId"> | undefined => {
    const canonical = canonicalChord(chord);
    if (canonical === undefined) {
      return { chord, reason: `"${chord}" is not a key combination` };
    }

    const bucket = bucketOf(action.mode, canonical);
    const holder = index.get(bucket);
    // The same chord twice in one list is a duplicate, not a conflict: dropped in silence.
    if (holder === action) return undefined;
    if (holder) {
      return { chord, reason: `already held by "${holder.label}"`, heldBy: holder.id };
    }

    index.set(bucket, action);
    keys[action.id] = [...(keys[action.id] ?? []), canonical];
    return undefined;
  };

  for (const action of ACTIONS) {
    if (remapped.has(action.id)) continue;
    // A default that cannot be laid down is impossible: `indexActions` throws on exactly
    // that at module load, which is why nothing here reports one.
    for (const chord of action.defaultKeys ?? []) lay(action, chord);
  }

  for (const action of ACTIONS) {
    if (!remapped.has(action.id)) continue;
    for (const chord of overrides?.[action.id] ?? []) {
      const refusal = lay(action, chord);
      if (refusal) rejected.push({ actionId: action.id, ...refusal });
    }
  }

  return { keys, index, rejected };
}

/**
 * The defaults, indexed. The dispatcher's starting point before `/api/me` lands, and what
 * the unit suite resolves against.
 */
export const DEFAULT_MERGE: MergedBindings = mergeBindings();

/**
 * The action a chord means on [mode], before `when` is consulted.
 *
 * The mode's own bucket first, then the shared one, so a screen can claim a chord without
 * the chords every screen answers having to be repeated in each. `savedView:x` selects a
 * row; `any:x` archives a ticket; the reader on a saved view gets the first and everybody
 * else the second, which is the whole reason `mode` is not just `View`.
 */
export function resolveShortcut(
  chord: string,
  mode: ShortcutMode,
  index: BindingIndex,
): Action | undefined {
  return index.get(bucketOf(mode, chord)) ?? index.get(bucketOf(undefined, chord));
}

/** The chords that currently reach [action] — the effective ones, not its defaults. */
export function chordsOf(action: Action, keys: Bindings): readonly string[] {
  return chordsFor(action.id, keys);
}

/**
 * The same, by id, for the two callers that have an id and no action: the status bar,
 * which names the ten it prints, and the dispatcher, which asks what `app.back` is bound
 * to before it has resolved anything.
 */
export function chordsFor(id: string, keys: Bindings): readonly string[] {
  return keys[id] ?? [];
}

/**
 * The key to print beside [action], or nothing when the keyboard does not reach it.
 *
 * One function for the three surfaces that used to spell this out themselves — the help
 * sheet, the row menus and the ticket list's status bar, which printed ten keys as string
 * literals and would have confidently advertised a keyboard the reader had remapped.
 * `formatChord` is the only place a chord becomes text; this is the only place an *action*
 * becomes text.
 *
 * The first spelling only: `ticket.moveDown` answers both `n` and `↓`, and a menu entry
 * reading "n ↓" teaches nothing. The help sheet wants both and uses [shortcutRows].
 */
export function hintFor(action: Action, keys: Bindings, isMac: boolean): string | undefined {
  const first = chordsOf(action, keys)[0];
  return first === undefined ? undefined : formatChord(first, isMac);
}

/**
 * Rows for the help overlay, generated from the effective bindings.
 *
 * Each row carries its mode — undefined for the chords every screen answers — because a
 * flat list would offer the chart's `h` `l` `⇧h` `⇧l` to somebody in the list, where they
 * do nothing at all. `components/shortcut-sections.ts` holds the headings, and its test is
 * what stops a mode from being added without one.
 *
 * `?` reads the **effective** bindings, so it can never describe a keyboard the reader
 * does not have. That is the property the whole slice is for: the sheet is the authority
 * on which keys exist, so a sheet that is out of date is worse than no sheet.
 */
export function shortcutRows(
  keys: Bindings,
  isMac: boolean,
): { mode: ShortcutMode | undefined; keys: string; label: string }[] {
  return ACTIONS.flatMap((action) => {
    const chords = chordsOf(action, keys);
    if (chords.length === 0) return [];
    return [
      {
        mode: action.mode,
        keys: chords.map((chord) => formatChord(chord, isMac)).join(" / "),
        label: action.label,
      },
    ];
  });
}
