import { describe, expect, it } from "vitest";
import { extend, toggle } from "./selection";

/**
 * Screen 21's two selection gestures, from the shortcut sheet: `x` toggles one row and
 * `⇧↑↓` extends a range. Pure functions over id arrays, because the vitest run has no DOM
 * and the interesting part was never rendering — it is what "extend" means when the
 * anchor moved.
 */
const rows = ["a", "b", "c", "d", "e"];

describe("toggle", () => {
  it("adds a row that was not selected", () => {
    expect(toggle([], "c")).toEqual(["c"]);
  });

  it("removes a row that was", () => {
    expect(toggle(["a", "c"], "c")).toEqual(["a"]);
  });

  // The strip at the bottom is rendered when the selection is non-empty, so the last `x`
  // has to leave an empty array rather than a one-element one — otherwise the strip never
  // goes away and `esc` becomes the only way out of a mode nobody meant to enter.
  it("leaves nothing behind when the last row is untoggled", () => {
    expect(toggle(["c"], "c")).toEqual([]);
  });
});

describe("extend", () => {
  it("selects the span between the cursor and where it is going", () => {
    expect(extend(rows, [], "b", 1)).toEqual(["b", "c"]);
  });

  it("walks upwards as readily as down", () => {
    expect(extend(rows, [], "d", -1)).toEqual(["c", "d"]);
  });

  // Growing a range then shrinking it has to give back what it started with. Without this,
  // `⇧↓⇧↑` leaves two rows selected and the count in the strip is wrong by one for the
  // rest of the session.
  it("keeps what was already selected and adds the next row", () => {
    expect(extend(rows, ["b", "c"], "c", 1)).toEqual(["b", "c", "d"]);
  });

  it("stops at the ends of the list rather than wrapping", () => {
    expect(extend(rows, [], "e", 1)).toEqual(["e"]);
    expect(extend(rows, [], "a", -1)).toEqual(["a"]);
  });

  it("returns the rows in list order, not in the order they were touched", () => {
    expect(extend(rows, ["d"], "b", -1)).toEqual(["a", "b", "d"]);
  });

  // A row that was filtered away between two keypresses is not a row `⇧↓` can reach, and
  // silently keeping it in the selection would make the strip act on something invisible.
  it("drops a selected id the list no longer holds", () => {
    expect(extend(rows, ["b", "zzz"], "b", 1)).toEqual(["b", "c"]);
  });

  it("does nothing when the cursor is on no row at all", () => {
    expect(extend(rows, ["b"], undefined, 1)).toEqual(["b"]);
  });
});
