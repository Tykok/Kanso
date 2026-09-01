import { describe, expect, it } from "vitest";
import { flatIndexAt, flatIndexOf, flatten, placeOf, sizeAt, type FlatGroup } from "./virtual";

/**
 * The index a virtualised list is addressed by.
 *
 * A virtualiser hands out integers: it asks "what is at 41" and is told "scroll to 41".
 * Every question the keyboard asks is about a ticket instead — `j` moves to a row, `1`
 * sets the status of the selected one — so the whole correctness of a virtualised list
 * comes down to whether those two vocabularies convert without loss. These assert that
 * they do, in both directions, including for rows that are nowhere near the screen.
 */

/** The shape `grouping.ts` produces, with only the field the flattener reads. */
const group = (key: string, label: string, ids: string[]): FlatGroup<{ id: string }> => ({
  key,
  label,
  count: ids.length,
  tickets: ids.map((id) => ({ id })),
});

const idOf = (item: { id: string }) => item.id;

describe("flatten", () => {
  it("puts a header before each group's rows, in the order the groups arrived", () => {
    const flat = flatten([group("todo", "Todo", ["a", "b"]), group("done", "Done", ["c"])], idOf);

    expect(flat.map((entry) => entry.kind)).toEqual(["header", "row", "row", "header", "row"]);
    expect(flat.map((entry) => (entry.kind === "header" ? entry.label : entry.item.id))).toEqual([
      "Todo",
      "a",
      "b",
      "Done",
      "c",
    ]);
  });

  /**
   * `groupTickets(rows, "none")` answers with one group whose label is empty — a flat
   * list is a real choice on the group-by control, not the absence of one. Emitting a
   * header for it would put a blank caption above every ungrouped list in the app.
   */
  it("writes no header for a group that has no label", () => {
    const flat = flatten([group("", "", ["a", "b"])], idOf);
    expect(flat.map((entry) => entry.kind)).toEqual(["row", "row"]);
  });

  it("carries the count so a header can be drawn without going back to the group", () => {
    const [header] = flatten([group("todo", "Todo", ["a", "b", "c"])], idOf);
    expect(header).toMatchObject({ kind: "header", label: "Todo", count: 3, group: 0 });
  });

  it("gives every entry a key of its own, headers included", () => {
    const flat = flatten([group("todo", "Todo", ["a"]), group("done", "Done", ["b"])], idOf);
    expect(new Set(flat.map((entry) => entry.key)).size).toBe(flat.length);
  });

  it("has nothing to draw for no groups", () => {
    expect(flatten([], idOf)).toEqual([]);
  });
});

describe("flatIndexOf", () => {
  /**
   * The single most important line in this file. `j` at the bottom of a 500-row list
   * selects a ticket whose element does not exist, and the only way the cursor stays on
   * screen is for the list to resolve that ticket to an integer anyway. If this returned
   * -1 for an unmounted row, keyboard navigation would silently stop working past the
   * first screenful — which is worse than not virtualising at all.
   */
  it("resolves a row that is nowhere near the screen", () => {
    const ids = Array.from({ length: 500 }, (_, at) => `t${at}`);
    const flat = flatten([group("todo", "Todo", ids)], idOf);

    // +1 for the header the group put in front of its rows.
    expect(flatIndexOf(flat, "t0")).toBe(1);
    expect(flatIndexOf(flat, "t499")).toBe(500);
  });

  it("counts the headers between the groups it skips over", () => {
    const flat = flatten(
      [group("todo", "Todo", ["a", "b"]), group("done", "Done", ["c", "d"])],
      idOf,
    );
    expect(flatIndexOf(flat, "c")).toBe(4);
  });

  /** A row the filter removed between two keypresses is not a row to scroll to. */
  it("is -1 for a row the list no longer holds, and for no row at all", () => {
    const flat = flatten([group("todo", "Todo", ["a"])], idOf);
    expect(flatIndexOf(flat, "gone")).toBe(-1);
    expect(flatIndexOf(flat, undefined)).toBe(-1);
  });

  /** A header is not somewhere the cursor can be, so its key must not answer as a row. */
  it("does not answer with a header", () => {
    const flat = flatten([group("todo", "Todo", ["a"])], idOf);
    expect(flatIndexOf(flat, flat[0].key)).toBe(-1);
  });
});

describe("placeOf and flatIndexAt", () => {
  const flat = flatten(
    [group("todo", "Todo", ["a", "b"]), group("done", "Done", ["c"])],
    idOf,
  );

  it("maps a flat index back to the group and the rank inside it", () => {
    expect(placeOf(flat, 1)).toEqual({ group: 0, rank: 0 });
    expect(placeOf(flat, 2)).toEqual({ group: 0, rank: 1 });
    expect(placeOf(flat, 4)).toEqual({ group: 1, rank: 0 });
  });

  it("maps a group and a rank forward to the same flat index", () => {
    expect(flatIndexAt(flat, 0, 0)).toBe(1);
    expect(flatIndexAt(flat, 0, 1)).toBe(2);
    expect(flatIndexAt(flat, 1, 0)).toBe(4);
  });

  /** The two are one conversion read in two directions; a round trip must be identity. */
  it("round-trips every row in the list", () => {
    for (let index = 0; index < flat.length; index += 1) {
      const place = placeOf(flat, index);
      if (place === undefined) continue;
      expect(flatIndexAt(flat, place.group, place.rank)).toBe(index);
    }
  });

  it("has no place for a header, or for an index off either end", () => {
    expect(placeOf(flat, 0)).toBeUndefined();
    expect(placeOf(flat, -1)).toBeUndefined();
    expect(placeOf(flat, flat.length)).toBeUndefined();
  });

  it("is -1 for a group or a rank the list does not have", () => {
    expect(flatIndexAt(flat, 9, 0)).toBe(-1);
    expect(flatIndexAt(flat, 0, 9)).toBe(-1);
  });
});

describe("sizeAt", () => {
  /**
   * The estimate the virtualiser lays the scrollbar out from before anything is
   * measured. A header is taller than a row, and a list that guessed one number for
   * both would put the scrollbar in the wrong place on every grouped view.
   */
  it("answers a row's height for a row and a header's for a header", () => {
    const flat = flatten([group("todo", "Todo", ["a"])], idOf);
    const metrics = { row: 38, header: 41 };

    expect(sizeAt(flat, 0, metrics)).toBe(41);
    expect(sizeAt(flat, 1, metrics)).toBe(38);
  });

  /** The virtualiser asks about indexes that are briefly out of range while data swaps. */
  it("falls back on a row's height off the end rather than throwing", () => {
    expect(sizeAt([], 3, { row: 38, header: 41 })).toBe(38);
  });
});
