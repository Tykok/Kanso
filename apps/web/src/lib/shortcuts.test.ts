import { describe, expect, it } from "vitest";
import { ACTIONS, actionById, indexActions, type Action, type ShortcutMode } from "./actions";
import {
  canonicalChord,
  chordOf,
  chordsFor,
  DEFAULT_BINDINGS,
  DEFAULT_MERGE,
  formatChord,
  hintFor,
  mergeBindings,
  parseChord,
  resolveShortcut,
  shortcutRows,
} from "./shortcuts";

/**
 * The merge is what every keystroke in the application runs through, and it is the one
 * part of this slice where being wrong is *silent*: a refused binding that should have
 * been applied looks exactly like a reader misremembering their own keyboard, and a
 * binding applied that should have been refused turns one printed key into two actions.
 *
 * `mergeBindings` also has a rule the rest of the app does not: it never throws.
 * `indexActions` crashes the build over a developer's typo — correctly, since a build that
 * ships an ambiguous key is a build nobody should run — and this takes the same input
 * shape from a reader's stored preference, where a crash would mean an account that cannot
 * open the interface at all. Every assertion below about a refusal is therefore also an
 * assertion that nothing was thrown.
 */

/** A keypress, from a chord. Proves a chord is a thing a keyboard can actually send. */
function eventFor(chord: string) {
  const parsed = parseChord(chord);
  if (!parsed) throw new Error(`Not a chord: "${chord}"`);
  const letter = parsed.key.length === 1 && /^[a-z]$/.test(parsed.key);
  return {
    // A browser reports the *shifted* character, so a letter arrives uppercased and a
    // named key arrives unchanged. That asymmetry is the whole of `chordOf`'s job.
    key: parsed.shift && letter ? parsed.key.toUpperCase() : parsed.key,
    shiftKey: parsed.shift,
    metaKey: parsed.mod,
    ctrlKey: false,
    altKey: parsed.alt,
  };
}

const chordsIn = (bindings: Record<string, readonly string[]>) =>
  Object.values(bindings).flat();

describe("the chord grammar", () => {
  it("reads a bare key, a named key and a symbol", () => {
    expect(parseChord("n")).toEqual({ mod: false, alt: false, shift: false, key: "n" });
    expect(parseChord("Enter")).toEqual({ mod: false, alt: false, shift: false, key: "Enter" });
    expect(parseChord("?")).toEqual({ mod: false, alt: false, shift: false, key: "?" });
    expect(parseChord("[")).toEqual({ mod: false, alt: false, shift: false, key: "[" });
  });

  it("reads a modifier as a prefix", () => {
    expect(parseChord("Mod+k")).toMatchObject({ mod: true, key: "k" });
    expect(parseChord("Shift+e")).toMatchObject({ shift: true, key: "e" });
    expect(parseChord("Shift+ArrowDown")).toMatchObject({ shift: true, key: "ArrowDown" });
  });

  /**
   * The retired spelling, refused. `"E"` was how the old registry wrote Shift+e — the
   * `event.key` of the press — and it is exactly one spelling too many: accepting it would
   * leave two ways to write one chord, which is the ambiguity a capture UI cannot guess
   * its way out of.
   */
  it("refuses a bare uppercase letter, which is the spelling being retired", () => {
    expect(parseChord("E")).toBeUndefined();
    expect(parseChord("Shift+E")).toBeUndefined();
    expect(parseChord("H")).toBeUndefined();
    expect(parseChord("Shift+e")).toBeDefined();
  });

  it("refuses a modifier that is not one, a prefix twice, and an empty key", () => {
    expect(parseChord("Cmd+k")).toBeUndefined();
    expect(parseChord("Ctrl+k")).toBeUndefined();
    expect(parseChord("Meta+k")).toBeUndefined();
    expect(parseChord("Mod+Mod+k")).toBeUndefined();
    expect(parseChord("Mod+")).toBeUndefined();
    expect(parseChord("")).toBeUndefined();
  });

  /** `+` is a key somebody can press, so the prefixes are read from the left. */
  it("lets the plus sign itself be the key", () => {
    expect(parseChord("Mod++")).toEqual({ mod: true, alt: false, shift: false, key: "+" });
  });

  it("re-spells a chord in canonical prefix order", () => {
    expect(canonicalChord("Shift+Mod+f")).toBe("Mod+Shift+f");
    expect(canonicalChord("Shift+Alt+Mod+f")).toBe("Mod+Alt+Shift+f");
    expect(canonicalChord("Mod+k")).toBe("Mod+k");
    expect(canonicalChord("E")).toBeUndefined();
  });
});

describe("the chord a keypress means", () => {
  it("lowercases a letter and makes the shift a prefix", () => {
    // `event.key` for Shift+e is "E" in every browser. The prefix is what makes it
    // parseable, and lowercasing is what stops "E" being a second spelling of it.
    expect(chordOf({ key: "E", shiftKey: true, metaKey: false, ctrlKey: false, altKey: false })).toBe(
      "Shift+e",
    );
    expect(chordOf({ key: "e", shiftKey: false, metaKey: false, ctrlKey: false, altKey: false })).toBe(
      "e",
    );
  });

  /**
   * The load-bearing exception, and the reason `?` still opens the help sheet.
   *
   * `?` *is* Shift+/ on a US layout, but the layout has already produced the character —
   * `event.key` is `"?"` — and another layout produces it with no Shift at all. So a
   * symbol carries no prefix: `"Shift+?"` would name a chord no keyboard can send.
   */
  it("leaves a symbol alone, shift or no shift", () => {
    expect(chordOf({ key: "?", shiftKey: true, metaKey: false, ctrlKey: false, altKey: false })).toBe(
      "?",
    );
    expect(chordOf({ key: "!", shiftKey: true, metaKey: false, ctrlKey: false, altKey: false })).toBe(
      "!",
    );
    expect(chordOf({ key: "1", shiftKey: false, metaKey: false, ctrlKey: false, altKey: false })).toBe(
      "1",
    );
  });

  /**
   * A named key is unaffected by Shift, so the flag is all there is to read — which is
   * what makes `⇧↓` and `⇧↵` expressible at all. `saved-view.tsx` kept its own handler for
   * a whole slice because of exactly this: "`event.key` for Shift+ArrowDown is still
   * `ArrowDown`, which is why this cannot be a registry shortcut".
   */
  it("makes shift a prefix on a named key, which is what ⇧↓ and ⇧↵ needed", () => {
    expect(
      chordOf({ key: "ArrowDown", shiftKey: true, metaKey: false, ctrlKey: false, altKey: false }),
    ).toBe("Shift+ArrowDown");
    expect(
      chordOf({ key: "Enter", shiftKey: true, metaKey: false, ctrlKey: false, altKey: false }),
    ).toBe("Shift+Enter");
    expect(
      chordOf({ key: "Enter", shiftKey: false, metaKey: false, ctrlKey: false, altKey: false }),
    ).toBe("Enter");
  });

  /** One flag for both platforms: `⌘` and `Ctrl` are the same intention. */
  it("reads Mod off either modifier, so one chord reaches both platforms", () => {
    expect(chordOf({ key: "k", shiftKey: false, metaKey: true, ctrlKey: false, altKey: false })).toBe(
      "Mod+k",
    );
    expect(chordOf({ key: "k", shiftKey: false, metaKey: false, ctrlKey: true, altKey: false })).toBe(
      "Mod+k",
    );
  });

  it("spells the prefixes in canonical order whatever is held", () => {
    expect(chordOf({ key: "F", shiftKey: true, metaKey: true, ctrlKey: false, altKey: true })).toBe(
      "Mod+Alt+Shift+f",
    );
  });

  /**
   * The property that matters more than any single case: every key the app ships is a key
   * a keyboard can send. A default nothing can produce is a key printed in the `?` sheet
   * that answers nothing, and it would be invisible in every other test here.
   */
  it("round-trips every default binding through a synthesised keypress", () => {
    for (const chord of chordsIn(DEFAULT_BINDINGS)) {
      expect(chordOf(eventFor(chord)), chord).toBe(chord);
    }
  });
});

describe("printing a chord", () => {
  it("expands Mod per platform, which is the one thing the registry cannot know", () => {
    expect(formatChord("Mod+k", true)).toBe("⌘K");
    expect(formatChord("Mod+k", false)).toBe("Ctrl+K");
    expect(formatChord("Mod+f", true)).toBe("⌘F");
    expect(formatChord("Mod+f", false)).toBe("Ctrl+F");
    expect(formatChord("Mod+v", true)).toBe("⌘V");
    expect(formatChord("Mod+v", false)).toBe("Ctrl+V");
  });

  /**
   * A letter under `Mod` is uppercased because `⌘K` and `Ctrl+F` are how both platforms
   * print their own menus. A letter under `Shift` is not: `⇧e` reads as "shift and e",
   * where `⇧E` would put the case back into the letter and re-open the ambiguity the
   * prefix grammar removes.
   */
  it("uppercases a letter under Mod and leaves one under Shift alone", () => {
    expect(formatChord("Shift+e", true)).toBe("⇧e");
    expect(formatChord("Shift+e", false)).toBe("⇧e");
    expect(formatChord("Shift+h", true)).toBe("⇧h");
    // With Mod also held the letter goes back up, because `⌘⇧E` is what macOS prints in
    // its own menus. No default combines the two; this is written down so the composition
    // is not discovered by whoever first binds one in Settings.
    expect(formatChord("Mod+Shift+e", true)).toBe("⌘⇧E");
    expect(formatChord("Mod+Shift+e", false)).toBe("Ctrl+⇧E");
  });

  it("draws the named keys as the glyphs a reader knows", () => {
    expect(formatChord("ArrowDown", true)).toBe("↓");
    expect(formatChord("ArrowUp", true)).toBe("↑");
    expect(formatChord("ArrowLeft", true)).toBe("←");
    expect(formatChord("ArrowRight", true)).toBe("→");
    expect(formatChord("Enter", true)).toBe("↵");
    expect(formatChord("Shift+Enter", true)).toBe("⇧↵");
    expect(formatChord("Shift+ArrowDown", true)).toBe("⇧↓");
    expect(formatChord("Escape", true)).toBe("Esc");
  });

  it("leaves a bare key as itself", () => {
    expect(formatChord("n", true)).toBe("n");
    expect(formatChord("?", true)).toBe("?");
    expect(formatChord("[", true)).toBe("[");
    expect(formatChord("1", true)).toBe("1");
  });

  /** A display function: an empty box beside a word teaches less than a strange one. */
  it("returns a string it cannot parse unchanged", () => {
    expect(formatChord("E", true)).toBe("E");
    expect(formatChord("Cmd+k", true)).toBe("Cmd+k");
  });
});

describe("the defaults, derived from the registry", () => {
  it("carries every action that declares a key, and nothing else", () => {
    const declared = ACTIONS.filter((action) => (action.defaultKeys ?? []).length > 0);
    expect(Object.keys(DEFAULT_BINDINGS).sort()).toEqual(declared.map((a) => a.id).sort());
    for (const action of declared) {
      expect(DEFAULT_BINDINGS[action.id]).toEqual([...(action.defaultKeys ?? [])]);
    }
  });

  it("lays every one of them down without a refusal", () => {
    expect(DEFAULT_MERGE.rejected).toEqual([]);
  });

  /** `{}` is the honest representation of "I never changed anything", and the common case. */
  it("is what an empty override object and no override at all both produce", () => {
    expect(mergeBindings({}).keys).toEqual(DEFAULT_BINDINGS);
    expect(mergeBindings().keys).toEqual(DEFAULT_BINDINGS);
    expect(mergeBindings(undefined).keys).toEqual(DEFAULT_BINDINGS);
  });
});

describe("merging a reader's overrides", () => {
  /** An override *replaces* the action's chords. It is not a list something is added to. */
  it("replaces an action's keys rather than adding to them", () => {
    const { keys, rejected } = mergeBindings({ "ticket.moveDown": ["Mod+j"] });
    expect(keys["ticket.moveDown"]).toEqual(["Mod+j"]);
    expect(rejected).toEqual([]);
  });

  /**
   * And the key it gave up is free, immediately. Otherwise remapping would be impossible
   * without also clearing whoever else wanted the key — the reader would have to guess the
   * right order to make two changes in.
   */
  it("frees the default the override replaced, in the same merge", () => {
    const { keys, rejected, index } = mergeBindings({
      "ticket.rename": ["r"],
      "ticket.create": ["e"],
    });
    expect(keys["ticket.rename"]).toEqual(["r"]);
    expect(keys["ticket.create"]).toEqual(["e"]);
    expect(rejected).toEqual([]);
    expect(resolveShortcut("e", "list", index)?.id).toBe("ticket.create");
    expect(resolveShortcut("c", "list", index)).toBeUndefined();
  });

  it("leaves an action keyless when the override is an empty list", () => {
    const { keys, rejected, index } = mergeBindings({ "ticket.rename": [] });
    expect(keys["ticket.rename"]).toBeUndefined();
    expect(chordsFor("ticket.rename", keys)).toEqual([]);
    expect(rejected).toEqual([]);
    expect(resolveShortcut("e", "list", index)).toBeUndefined();
  });

  it("drops a chord repeated inside one override without calling it a conflict", () => {
    const { keys, rejected } = mergeBindings({ "ticket.rename": ["r", "r"] });
    expect(keys["ticket.rename"]).toEqual(["r"]);
    expect(rejected).toEqual([]);
  });

  /**
   * Non-canonical spelling from storage, which is the case the two guards answer
   * differently on purpose: `indexActions` throws on it, because a developer who typed it
   * has a bug; this accepts and re-spells it, because a reader whose stored row says
   * `Shift+Mod+e` should get the key they asked for and not a broken interface.
   */
  it("re-spells a stored chord rather than refusing it, where a default would throw", () => {
    const { keys, rejected, index } = mergeBindings({ "ticket.rename": ["Shift+Mod+e"] });
    expect(keys["ticket.rename"]).toEqual(["Mod+Shift+e"]);
    expect(rejected).toEqual([]);
    expect(resolveShortcut("Mod+Shift+e", "list", index)?.id).toBe("ticket.rename");

    const asADefault: Action[] = [
      {
        id: "test.typo",
        label: "Typo",
        defaultKeys: ["Shift+Mod+e"],
        group: "view",
        when: () => true,
        run: () => {},
      },
    ];
    expect(() => indexActions(asADefault)).toThrow(/not a chord/);
  });
});

describe("refusing a collision, with its reason", () => {
  /**
   * "Never a silent steal." §6.5's capture UI refuses a chord already held and names its
   * holder; this is the same rule applied to data that arrived some other way — an older
   * release, a hand-edited row — and the reason string is what that settings table prints.
   */
  it("refuses an override that collides with a default, naming the holder", () => {
    const { keys, rejected, index } = mergeBindings({ "ticket.create": ["x"] });

    expect(rejected).toHaveLength(1);
    expect(rejected[0].actionId).toBe("ticket.create");
    expect(rejected[0].chord).toBe("x");
    expect(rejected[0].heldBy).toBe("ticket.archive");
    // The label, not the id: this sentence is read by a person in Settings, and
    // "ticket.archive" is not what the row above it is called.
    expect(rejected[0].reason).toContain(actionById("ticket.archive").label);

    // The holder keeps the key, and the refused claimant has none — its own default went
    // with the override that replaced it. A partial application would be worse: the reader
    // would have two keys doing one thing and no note saying why.
    expect(resolveShortcut("x", "list", index)?.id).toBe("ticket.archive");
    expect(keys["ticket.create"]).toBeUndefined();

    // The holder wins even though the registry lists the claimant *first*: every default
    // an override does not mention goes down before any override does, which is what makes
    // "who was here already" a question about the reader's keyboard and not about the
    // order six slice files happen to be composed in.
    expect(ACTIONS.findIndex((action) => action.id === "ticket.create")).toBeLessThan(
      ACTIONS.findIndex((action) => action.id === "ticket.archive"),
    );
  });

  it("keeps the rest of an override when one of its chords is refused", () => {
    const { keys, rejected } = mergeBindings({ "ticket.create": ["x", "Mod+n"] });
    expect(rejected.map((row) => row.chord)).toEqual(["x"]);
    expect(keys["ticket.create"]).toEqual(["Mod+n"]);
  });

  /**
   * Two modes may hold one chord, and a reader is allowed to arrange that. §6.5 refuses a
   * chord "already held **in the same mode**" — per mode, which is the registry's own rule
   * and not a stricter one — so `e` renaming in the list and creating a ticket on the board
   * is a legal arrangement: `list:e` and `any:e` are two buckets and the mode is asked
   * first.
   *
   * `core.test.ts` refuses this shape for the *defaults*, by policy, with its exceptions
   * written out one id at a time. It is deliberately not refused here: the same mechanism
   * is what lets somebody put the saved view's row-select on `Mod+f` and keep the filter
   * dialog everywhere else, which is a remap a reader might reasonably want.
   */
  it("allows an override to shadow a shared key in one mode only", () => {
    const { keys, rejected, index } = mergeBindings({ "ticket.create": ["e"] });
    expect(rejected).toEqual([]);
    expect(keys["ticket.create"]).toEqual(["e"]);
    expect(resolveShortcut("e", "list", index)?.id).toBe("ticket.rename");
    expect(resolveShortcut("e", "board", index)?.id).toBe("ticket.create");
  });

  /** Two overrides wanting one chord: registry order decides, and the loser is told. */
  it("refuses the second of two overrides claiming one chord", () => {
    const { keys, rejected } = mergeBindings({
      "ticket.create": ["q"],
      "ticket.moveDown": ["q"],
    });
    expect(keys["ticket.create"]).toEqual(["q"]);
    expect(keys["ticket.moveDown"]).toBeUndefined();
    expect(rejected).toHaveLength(1);
    expect(rejected[0]).toMatchObject({
      actionId: "ticket.moveDown",
      chord: "q",
      heldBy: "ticket.create",
    });
  });

  it("refuses a chord that is not a chord, and says so rather than throwing", () => {
    const { keys, rejected } = mergeBindings({
      "ticket.rename": ["E", "Cmd+e", "", "Mod+", "r"],
    });
    expect(rejected.map((row) => row.chord)).toEqual(["E", "Cmd+e", "", "Mod+"]);
    for (const row of rejected) {
      expect(row.reason).toContain("not a key combination");
      expect(row.heldBy).toBeUndefined();
    }
    // The one legal chord in that list still lands, which is the point of refusing per
    // chord rather than per action.
    expect(keys["ticket.rename"]).toEqual(["r"]);
  });

  /**
   * Nothing a stored value can be may throw. This is the assertion that stands behind the
   * whole "refused, never thrown" rule, since the failure it guards against is an account
   * that cannot open the app at all.
   */
  it("survives every shape a hand-edited preference can hold", () => {
    expect(() =>
      mergeBindings({
        "ticket.rename": ["", "+", "Mod+", "🙂", "Shift+", "a+b"],
        "no.such.action": ["z"],
        "": ["z"],
      }),
    ).not.toThrow();
  });

  /**
   * An action deleted in a later version must not make a stored preference unreadable, and
   * it is not reported either: there is nothing the reader could do about an id this build
   * has never heard of, and a settings page listing it would be an apology for our rename.
   */
  it("ignores an unknown action id without a word", () => {
    const { keys, rejected } = mergeBindings({
      "ticket.renameV2": ["r"],
      "organise.clearSelection": ["Escape"],
    });
    expect(rejected).toEqual([]);
    expect(keys).toEqual(DEFAULT_BINDINGS);
  });

  /** An unknown id must not free the key of a live one that happens to look like it. */
  it("leaves every live binding alone while ignoring an unknown one", () => {
    const { index } = mergeBindings({ "ticket.rename.v2": ["e"] });
    expect(resolveShortcut("e", "list", index)?.id).toBe("ticket.rename");
  });
});

describe("resolving a chord", () => {
  const at = (chord: string, mode: ShortcutMode, index = DEFAULT_MERGE.index) =>
    resolveShortcut(chord, mode, index);

  it("asks the mode's own bucket before the shared one", () => {
    expect(at("x", "list")?.id).toBe("ticket.archive");
    expect(at("x", "savedView")?.id).toBe("organise.select");
    expect(at("x", "triage")?.id).toBe("triage.reject");
    expect(at("x", "board")?.id).toBe("ticket.archive");
  });

  it("falls back to the shared bucket for a mode that claims nothing", () => {
    expect(at("?", "triage")?.id).toBe("app.help");
    expect(at("Mod+k", "savedView")?.id).toBe("app.palette");
    expect(at("c", "timeline")?.id).toBe("ticket.create");
  });

  /**
   * The same precedence on a *remapped* key, which is the case no default can exercise: a
   * reader who moves `Mod+f` onto the saved view's row-select must still get the filter
   * dialog everywhere else, because the two live in different buckets and the mode is asked
   * first.
   */
  it("keeps that precedence when the reader is the one who claimed the chord", () => {
    const { index } = mergeBindings({ "organise.select": ["Mod+f"] });
    expect(at("Mod+f", "savedView", index)?.id).toBe("organise.select");
    expect(at("Mod+f", "list", index)?.id).toBe("organise.addFilter");
    // And `x` is nobody's on a saved view now: the override replaced it.
    expect(at("x", "savedView", index)?.id).toBe("ticket.archive");
  });

  it("answers nothing for a chord no action holds", () => {
    expect(at("z", "list")).toBeUndefined();
    expect(at("j", "list")).toBeUndefined();
    expect(at("k", "list")).toBeUndefined();
    expect(at("Mod+z", "list")).toBeUndefined();
  });
});

/**
 * The property the whole slice exists for.
 *
 * `hint` and `shortcut` were two fields, one printed and one dispatched, and they diverged
 * twice: `organise.select` printed `x` and dispatched nothing, and the board's five keys
 * dispatched and were printed nowhere. There is one field now and one merge, and these are
 * the assertions that say the printed key and the answered key cannot come apart — on the
 * defaults, and on a keyboard the reader has changed.
 */
describe("what is printed and what is dispatched", () => {
  const agrees = (keys: Record<string, readonly string[]>, index: Parameters<typeof resolveShortcut>[2]) => {
    for (const action of ACTIONS) {
      const chords = chordsFor(action.id, keys);
      if (chords.length === 0) {
        expect(hintFor(action, keys, true), action.id).toBeUndefined();
        continue;
      }
      // What a menu prints is the first chord, and the first chord is one that resolves
      // back to this very action in the mode it belongs to.
      expect(hintFor(action, keys, true), action.id).toBe(formatChord(chords[0], true));
      for (const chord of chords) {
        expect(resolveShortcut(chord, action.mode ?? "list", index)?.id, chord).toBe(action.id);
      }
    }
  };

  it("agree on the keyboard as it ships", () => {
    agrees(DEFAULT_BINDINGS as Record<string, readonly string[]>, DEFAULT_MERGE.index);
  });

  it("agree on a keyboard the reader has rearranged", () => {
    const { keys, index, rejected } = mergeBindings({
      "ticket.rename": ["Mod+r"],
      "ticket.create": ["e"],
      "app.palette": ["Mod+p"],
      "organise.select": ["Mod+f"],
      "inbox.markAllRead": [],
    });
    expect(rejected).toEqual([]);
    agrees(keys as Record<string, readonly string[]>, index);
  });

  /** The `?` sheet reads the same map, so it cannot describe a keyboard nobody has. */
  it("are the same map the help sheet prints from", () => {
    const { keys } = mergeBindings({ "ticket.moveDown": ["Mod+ArrowDown"] });
    const rows = shortcutRows(keys, true);
    expect(rows.find((row) => row.label === "Move down")?.keys).toBe("⌘↓");
    // And the default it replaced is nowhere in the sheet's shared section. The board's own
    // "next card" still prints `n`, which is right: that is a different action in a
    // different bucket, and the reader remapped one of the two.
    expect(
      rows.filter((row) => row.mode === undefined).some((row) => row.keys.includes("n")),
    ).toBe(false);
    expect(rows.find((row) => row.label === "Next card in this column")?.keys).toBe("n / ↓");
  });

  it("print nothing at all for a key the reader has taken away", () => {
    const { keys } = mergeBindings({ "inbox.markAllRead": [] });
    expect(hintFor(actionById("inbox.markAllRead"), keys, true)).toBeUndefined();
    expect(shortcutRows(keys, true).some((row) => row.label === "Mark everything read")).toBe(
      false,
    );
  });
});
