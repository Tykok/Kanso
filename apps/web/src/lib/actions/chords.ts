/**
 * The chord grammar: one string that can be parsed, printed and captured.
 *
 * A chord is a `KeyboardEvent.key` value with zero or more modifier prefixes:
 *
 *     "n"  "Enter"  "?"  "Shift+e"  "Shift+ArrowDown"  "Mod+f"  "Mod+k"
 *
 * It replaces two fields that could disagree — `Action.shortcut`, a space-separated list
 * of bare keys, and `Action.hint`, a string printed but never dispatched. `hint` existed
 * because three intentions were inexpressible: `⌘K` needed a modifier, `⇧↑↓` needed a
 * modifier the registry had no way to read, and `x` on a saved view needed a route the
 * registry could not name. All three were written down as text somebody had to keep in
 * step with a handler in another file, and twice they were not.
 *
 * **Shift is a prefix, never a letter's case.** `event.key` for Shift+e is `"E"`, and the
 * old registry used that as the spelling — which works for letters and for nothing else:
 * `event.key` for Shift+ArrowDown is still `"ArrowDown"`, so `⇧↓` could not be written at
 * all. Writing the modifier out makes every chord parseable, which is what a capture UI
 * (§6.5) needs in order to *produce* one, and it means the two halves of a gesture read as
 * a pair — `"d"` and `"Shift+d"` rather than `"d"` and `"D"`.
 *
 * A bare uppercase letter is therefore **refused** rather than quietly re-spelled: `"E"`
 * is the retired spelling, and accepting it would leave two ways to write one chord — the
 * ambiguity this file exists to remove. Punctuation is the exception and is not ambiguous
 * at all: `?` *is* Shift+/ on a US layout, but `event.key` is already `"?"`, the shift is
 * part of the character the layout produced, and a different layout produces it without
 * Shift. So a symbol carries no prefix and `"?"` stays `"?"` on every keyboard.
 *
 * `Mod` is written rather than `⌘` or `Ctrl` because which one the reader has is a runtime
 * fact about the reader and this file is a module. It becomes text exactly once, in
 * [formatChord], which is the only place in the app where a key becomes something a person
 * reads — the property the merge of `shortcut` and `hint` was for.
 *
 * No imports, on purpose. `lib/shortcuts.ts` derives the bindings from the registry and so
 * imports it; the registry needs to print a key, so it imports this. Putting the grammar
 * in `lib/shortcuts.ts` would close that loop, and the loop is fatal rather than untidy:
 * `DEFAULT_BINDINGS` is computed at module load from `ACTIONS`, so whichever of the two
 * modules loaded second would read `ACTIONS` in its temporal dead zone and throw. Hence a
 * leaf. `lib/shortcuts.ts` re-exports everything here, so §6.5 has one import to write.
 */

/** A chord in canonical spelling. A plain string so it can be stored as JSON. */
export type Chord = string;

export type ParsedChord = {
  /** `⌘` on a Mac, `Ctrl` everywhere else — one flag, because no chord means both. */
  mod: boolean;
  shift: boolean;
  alt: boolean;
  /** The `KeyboardEvent.key` this chord dispatches on: `"n"`, `"Enter"`, `"["`, `"?"`. */
  key: string;
};

/**
 * What [chordOf] needs of an event, which is five fields and not a DOM class.
 *
 * Named so the dispatcher's tests can hand it an object literal: the unit suite runs
 * under `environment: "node"`, where `KeyboardEvent` does not exist.
 */
export type ChordEvent = {
  key: string;
  shiftKey: boolean;
  metaKey: boolean;
  ctrlKey: boolean;
  altKey: boolean;
};

/**
 * The prefixes, in the order a chord is spelled and printed.
 *
 * `⌘⌥⇧` is the order macOS prints its own menus in, and `Ctrl+Alt+Shift` is the order
 * Windows prints its own — the two agree, so one canonical order serves both and a chord
 * captured on one platform reads naturally on the other.
 */
const PREFIXES = ["Mod", "Alt", "Shift"] as const;

/** What a key is called on screen. The named keys only: a letter prints as itself. */
const KEY_LABELS: Record<string, string> = {
  ArrowDown: "↓",
  ArrowUp: "↑",
  ArrowLeft: "←",
  ArrowRight: "→",
  Enter: "↵",
  Escape: "Esc",
  " ": "Space",
};

const isLetter = (key: string) => key.length === 1 && /^[A-Za-z]$/.test(key);

/**
 * Whether [key] is a plausible `KeyboardEvent.key`: one character, or a named key.
 *
 * Deliberately a shape check and not a list. The named keys are open-ended — `F7`,
 * `PageDown`, `AudioVolumeMute` — and a closed list would refuse a chord the reader's
 * keyboard can actually produce, which is the one thing a capture UI must never do.
 */
const isKeyName = (key: string) => key.length === 1 || /^[A-Z][A-Za-z0-9]*$/.test(key);

/**
 * Reads a chord, or answers `undefined` for a string that is not one.
 *
 * `undefined` rather than a throw: this runs over stored user data, and one unreadable
 * entry must not cost the reader the rest of their keyboard. `mergeBindings` turns the
 * `undefined` into a refusal with a reason; `indexActions` turns it into a load-time
 * throw, because a chord a developer typed wrong is a bug and not a preference.
 */
export function parseChord(chord: string): ParsedChord | undefined {
  let rest = chord;
  const held = { mod: false, alt: false, shift: false };

  // Greedy from the left rather than `split("+")`, so `+` itself can be the key:
  // `"Mod++"` is Ctrl and the plus sign, and splitting would read it as two empty
  // segments and lose it.
  let matched = true;
  while (matched) {
    matched = false;
    for (const prefix of PREFIXES) {
      if (!rest.startsWith(`${prefix}+`)) continue;
      const flag = prefix.toLowerCase() as "mod" | "alt" | "shift";
      // A prefix twice is a typo, not an emphasis.
      if (held[flag]) return undefined;
      held[flag] = true;
      rest = rest.slice(prefix.length + 1);
      matched = true;
      break;
    }
  }

  if (rest.length === 0) return undefined;
  if (!isKeyName(rest)) return undefined;
  // The retired spelling. See this file's docstring: `"E"` has to be `"Shift+e"`, or
  // there are two ways to write one chord and a capture UI has to guess which.
  if (rest.length === 1 && /^[A-Z]$/.test(rest)) return undefined;

  return { ...held, key: rest };
}

/** Re-spells a chord in canonical prefix order, or `undefined` if it is not one. */
export function canonicalChord(chord: string): Chord | undefined {
  const parsed = parseChord(chord);
  return parsed && spell(parsed);
}

function spell({ mod, alt, shift, key }: ParsedChord): Chord {
  return `${mod ? "Mod+" : ""}${alt ? "Alt+" : ""}${shift ? "Shift+" : ""}${key}`;
}

/**
 * The chord a keypress means.
 *
 * The one subtlety is which side of the Shift line a key falls on, and it is decided by
 * what the layout produced rather than by the flag:
 *
 *  - A **letter** is lowercased and the flag becomes a prefix, so Shift+e is `"Shift+e"`
 *    and not `"E"`.
 *  - Any other **single character** keeps whatever the layout gave and carries no Shift
 *    prefix: `?`, `:`, `_` and `!` are already the shifted character, so prefixing them
 *    would name a chord no keyboard can send. This is why `?` still opens the help sheet.
 *  - A **named key** — `Enter`, `ArrowDown`, `Tab` — is unaffected by Shift, so the flag
 *    is all there is to read and it becomes a prefix. This is what makes `⇧↓` and `⇧↵`
 *    expressible, which is the whole reason two files had to keep their own handlers.
 *
 * `metaKey || ctrlKey` for `Mod`, so a chord written once reaches both platforms. Nothing
 * distinguishes them: a binding that meant ⌘ on a Mac and nothing on Linux would be a
 * fourth thing to keep in step, and no chord in the defaults wants Ctrl on a Mac.
 */
export function chordOf(event: ChordEvent): Chord {
  const named = event.key.length > 1;
  const letter = isLetter(event.key);
  return spell({
    mod: event.metaKey || event.ctrlKey,
    alt: event.altKey,
    shift: event.shiftKey && (named || letter),
    key: letter ? event.key.toLowerCase() : event.key,
  });
}

/**
 * The one place a chord becomes text.
 *
 * The help overlay, the row menus, the status bar and (from §6.5) the settings table all
 * come through here, so the app cannot print a key in two spellings — which it did, in
 * three files, for as long as `hint` and `shortcut` were separate fields.
 *
 * A letter under `Mod` is uppercased, because `⌘K` and `Ctrl+F` are how every platform
 * prints its own menus. A letter under `Shift` is **not**: `⇧e` reads as "shift and e",
 * where `⇧E` would put the case back into the letter and re-open the exact ambiguity the
 * prefix grammar removes.
 *
 * A string this cannot parse is returned unchanged. It is a display function, and an
 * empty box teaches less than a strange one.
 */
export function formatChord(chord: string, isMac: boolean): string {
  const parsed = parseChord(chord);
  if (!parsed) return chord;

  const key =
    KEY_LABELS[parsed.key] ?? (parsed.mod && isLetter(parsed.key) ? parsed.key.toUpperCase() : parsed.key);

  return [
    parsed.mod ? (isMac ? "⌘" : "Ctrl+") : "",
    parsed.alt ? (isMac ? "⌥" : "Alt+") : "",
    parsed.shift ? "⇧" : "",
    key,
  ].join("");
}
