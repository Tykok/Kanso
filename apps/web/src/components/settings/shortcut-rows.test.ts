import { describe, expect, it } from "vitest";
import { actionById, ACTIONS } from "@/lib/actions";
import { mergeBindings } from "@/lib/shortcuts";
import {
  GROUP_TITLES,
  MAX_CHORDS_PER_ACTION,
  isLockedChord,
  isModifierKey,
  matches,
  modeTitle,
  refuseChord,
  shortcutTable,
  withChord,
  withoutChord,
  withoutOverride,
} from "./shortcut-rows";

const merge = (overrides?: Record<string, string[]>) => mergeBindings(overrides);
const rowFor = (id: string, overrides?: Record<string, string[]>) => {
  const { keys } = merge(overrides);
  return shortcutTable(keys, overrides ?? {}, false)
    .flatMap((group) => group.rows)
    .find((row) => row.action.id === id);
};

/**
 * The table is the only screen that claims to list the whole keyboard, so the claim is
 * tested against the registry rather than against a fixture: an action added in a later
 * slice with a group nobody headed would otherwise be silently undrawable — the exact
 * failure `shortcut-sections.test.ts` exists to stop one surface further along.
 */
describe("the shortcuts table", () => {
  it("draws every action in the registry, once", () => {
    const drawn = shortcutTable(merge().keys, {}, false).flatMap((group) => group.rows);
    expect(drawn.map((row) => row.action.id).sort()).toEqual(ACTIONS.map((a) => a.id).sort());
  });

  it("heads every group the registry declares, and heads none it does not", () => {
    const declared = new Set(ACTIONS.map((action) => action.group));
    const headed = GROUP_TITLES.map(({ group }) => group);
    expect([...declared].filter((group) => !headed.includes(group))).toEqual([]);
    expect(headed.filter((group) => !declared.has(group))).toEqual([]);
    expect(new Set(headed).size).toBe(headed.length);
  });

  /**
   * Decision one, asserted rather than described: the palette-only actions are rows like
   * any other, so a reader can give `Delete ticket` a key. Their `chords` are empty, which
   * is what makes the row print `Not bound` instead of an empty cell.
   */
  it("draws the palette-only actions too, with no keys", () => {
    for (const id of ["ticket.delete", "timeline.schedule", "ticket.priority.urgent"]) {
      expect(rowFor(id)?.chords).toEqual([]);
    }
  });

  it("prints the keys as a person reads them, per platform", () => {
    expect(rowFor("app.palette")?.printed).toEqual(["Ctrl+K"]);
    const { keys } = merge();
    const mac = shortcutTable(keys, {}, true)
      .flatMap((group) => group.rows)
      .find((row) => row.action.id === "app.palette");
    expect(mac?.printed).toEqual(["⌘K"]);
  });

  it("names where a key works with the help sheet's own words", () => {
    expect(rowFor("ticket.create")?.where).toBe("Anywhere");
    expect(rowFor("board.columnLeft")?.where).toBe("On the board");
    expect(rowFor("triage.accept")?.where).toBe("In the triage queue");
    expect(modeTitle(undefined)).toBe("Anywhere");
  });

  it("says which rows the reader has spoken about", () => {
    expect(rowFor("ticket.create")?.overridden).toBe(false);
    expect(rowFor("ticket.create", { "ticket.create": ["Mod+n"] })?.overridden).toBe(true);
    // An explicit "no key" is still something the reader said, so the row can be reset.
    expect(rowFor("ticket.create", { "ticket.create": [] })?.overridden).toBe(true);
  });
});

describe("the search box", () => {
  const row = rowFor("organise.addFilter")!;

  it("matches a label, case-insensitively", () => {
    expect(matches(row, "filter")).toBe(true);
    expect(matches(row, "FILTER")).toBe(true);
    expect(matches(row, "priority")).toBe(false);
  });

  /**
   * Both spellings, and this is the point of searching keys at all: `mod` is what somebody
   * types, `⌘` is what the row shows, and a box that answered only one of the two would be
   * unsearchable by either the stored form or the visible one.
   */
  it("matches a key in the spelling that is stored and in the one that is printed", () => {
    expect(matches(row, "mod+f")).toBe(true);
    const mac = shortcutTable(merge().keys, {}, true)
      .flatMap((group) => group.rows)
      .find((r) => r.action.id === "organise.addFilter")!;
    expect(matches(mac, "⌘")).toBe(true);
  });

  it("matches the mode, so one screen's keys can be listed together", () => {
    expect(matches(rowFor("board.columnLeft")!, "board")).toBe(true);
  });

  it("keeps every row for an empty or blank query", () => {
    expect(matches(row, "")).toBe(true);
    expect(matches(row, "   ")).toBe(true);
  });
});

/**
 * The capture's refusals. Each one is a sentence a reader sees, so each is pinned: the
 * class of bug this guards is a rule enforced with the wrong message, which reads as the
 * interface refusing for no reason.
 */
describe("what a capture refuses", () => {
  const { keys, index } = merge();
  const refuse = (chord: string, id: string) => refuseChord(chord, actionById(id), keys, index);

  it("refuses a string that is not a chord", () => {
    expect(refuse("E", "ticket.delete")).toBe('"E" is not a key combination');
    expect(refuse("", "ticket.delete")).toBe('"" is not a key combination');
    // A modifier held on its own reaches `chordOf` as `Shift+Shift`, which *parses*; the
    // capture drops it before it gets here, and `isModifierKey` is that filter.
    expect(isModifierKey("Shift")).toBe(true);
    expect(isModifierKey("Meta")).toBe(true);
    expect(isModifierKey("s")).toBe(false);
  });

  it("refuses Escape and Tab, with or without modifiers", () => {
    const sentence = "Escape and Tab are how you get out of a capture, so neither can be assigned";
    expect(refuse("Escape", "ticket.delete")).toBe(sentence);
    expect(refuse("Tab", "ticket.delete")).toBe(sentence);
    expect(refuse("Shift+Tab", "ticket.delete")).toBe(sentence);
    expect(refuse("Mod+Escape", "ticket.delete")).toBe(sentence);
  });

  /**
   * Never a silent steal, in the merge's own words — the one rule §6.5 states twice.
   */
  it("refuses a chord another action holds in the same mode, and names it", () => {
    expect(refuse("c", "ticket.delete")).toBe('already held by "New ticket"');
    expect(refuse("Mod+k", "ticket.delete")).toBe('already held by "Command palette"');
  });

  it("allows a chord held only on another screen, which is what modes are for", () => {
    // `x` is `savedView`'s row selection and `triage`'s ruling; on the timeline it is free.
    expect(refuse("x", "timeline.schedule")).toBeUndefined();
    // And `h` belongs to the timeline and to the board; a saved view answers neither.
    expect(refuse("h", "organise.select")).toBeUndefined();
  });

  it("allows a bare printable key, which is the whole of §6.5's third rule", () => {
    expect(refuse("j", "ticket.delete")).toBeUndefined();
    expect(refuse("Shift+j", "ticket.delete")).toBeUndefined();
    expect(refuse("Mod+j", "ticket.delete")).toBeUndefined();
  });

  it("says so rather than refusing when the chord is already on this row", () => {
    expect(refuse("c", "ticket.create")).toBe("already reaches this action");
  });

  /**
   * `Escape` is the one key in the app that cannot be taken away — the dispatcher holds it
   * whatever storage says — so the row draws no remove beside it. Everything else can go.
   */
  it("locks Escape on app.back and nothing else", () => {
    expect(isLockedChord("app.back", "Escape")).toBe(true);
    expect(isLockedChord("ticket.create", "Escape")).toBe(false);
    expect(isLockedChord("app.back", "Mod+w")).toBe(false);
  });

  it("refuses a ninth chord, which is the column's limit and not a preference", () => {
    const many = ["j", "k", "q", "w", "y", "z", "Mod+j", "Mod+q"];
    const full = merge({ "ticket.delete": many });
    expect(full.rejected).toEqual([]);
    expect(refuseChord("v", actionById("ticket.delete"), full.keys, full.index)).toBe(
      `one action holds at most ${MAX_CHORDS_PER_ACTION} keys`,
    );
  });

  /**
   * A stored override that was refused is *not* a chord the row holds, so re-capturing it
   * has to be allowed — otherwise the reader is told the key is taken by the very entry
   * they are trying to replace, and the standing refusal becomes unfixable from the page
   * that exists to fix it.
   */
  it("lets a refused stored chord be captured again once its holder is free", () => {
    const collided = merge({ "ticket.delete": ["c"] });
    expect(collided.rejected).toEqual([
      { actionId: "ticket.delete", chord: "c", reason: 'already held by "New ticket"', heldBy: "ticket.create" },
    ]);
    const freed = merge({ "ticket.delete": ["c"], "ticket.create": [] });
    expect(freed.rejected).toEqual([]);
    expect(refuseChord("c", actionById("ticket.delete"), freed.keys, freed.index)).toBe(
      "already reaches this action",
    );
  });
});

/**
 * The writes. Every one of them produces a complete list for one action and touches no
 * other id — the two halves of "overrides only, never a copy of the defaults".
 */
describe("what a click stores", () => {
  it("adds a chord to what the action already answers, keeping the other spellings", () => {
    const { keys } = merge();
    expect(withChord({}, keys, "ticket.moveDown", "j")).toEqual({
      "ticket.moveDown": ["n", "ArrowDown", "j"],
    });
  });

  it("canonicalises what it stores, so one chord has one spelling on the wire", () => {
    const { keys } = merge();
    expect(withChord({}, keys, "ticket.delete", "Shift+Mod+x")).toEqual({
      "ticket.delete": ["Mod+Shift+x"],
    });
  });

  it("names only the action that changed", () => {
    const { keys } = merge();
    const stored = withChord({}, keys, "ticket.delete", "j");
    expect(Object.keys(stored)).toEqual(["ticket.delete"]);
    // And the untouched actions still come from the registry, which is the property a
    // stored copy of the defaults would destroy.
    expect(mergeBindings(stored).keys["ticket.create"]).toEqual(["c"]);
  });

  it("removes one chord and leaves the rest of the row alone", () => {
    const { keys } = merge();
    expect(withoutChord({}, keys, "ticket.moveDown", "n")).toEqual({
      "ticket.moveDown": ["ArrowDown"],
    });
  });

  /** An empty list is the only way to say "no key", and the merge reads it that way. */
  it("stores an empty list when the last chord goes, which unbinds the action", () => {
    const { keys } = merge();
    const unbound = withoutChord({}, keys, "view.cycleDrawing", "Mod+v");
    expect(unbound).toEqual({ "view.cycleDrawing": [] });
    const after = mergeBindings(unbound);
    expect(after.keys["view.cycleDrawing"]).toBeUndefined();
    expect(after.rejected).toEqual([]);
  });

  it("does not carry a refused chord forward into the next write", () => {
    const collided = { "ticket.delete": ["c", "j"] };
    const { keys } = merge(collided);
    // `c` was refused on load, so the row answers `j` alone and the next write says so.
    expect(withChord(collided, keys, "ticket.delete", "q")).toEqual({
      "ticket.delete": ["j", "q"],
    });
  });

  it("hands a row back to the registry on reset, and never a hardcoded default", () => {
    const overrides = { "ticket.create": ["Mod+n"], "ticket.delete": ["j"] };
    expect(withoutOverride(overrides, "ticket.create")).toEqual({ "ticket.delete": ["j"] });
    expect(mergeBindings(withoutOverride(overrides, "ticket.create")).keys["ticket.create"]).toEqual(
      ["c"],
    );
  });

  it("resets an action whose default is nothing back to nothing", () => {
    const after = mergeBindings(withoutOverride({ "timeline.schedule": ["j"] }, "timeline.schedule"));
    expect(after.keys["timeline.schedule"]).toBeUndefined();
  });

  it("never writes into the object it was handed", () => {
    const overrides = Object.freeze({ "ticket.delete": Object.freeze(["j"]) as readonly string[] });
    const { keys } = merge({ "ticket.delete": ["j"] });
    expect(() => withChord(overrides, keys, "ticket.create", "q")).not.toThrow();
    expect(() => withoutOverride(overrides, "ticket.delete")).not.toThrow();
    expect(overrides["ticket.delete"]).toEqual(["j"]);
  });
});
