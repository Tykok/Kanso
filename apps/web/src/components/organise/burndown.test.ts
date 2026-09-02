import { describe, expect, it } from "vitest";
import type { RemainingDay } from "@/lib/api";
import { bars, heights, LOAD_BAR_ORDER, progressSegments } from "./burndown";

/**
 * Screen 19's two charts, as geometry.
 *
 * The server says how many tickets are open on each day and which days are a projection;
 * this turns that into percentages a `<span>` can be tall. Separated out because a height
 * that comes back wrong is the kind of bug that is invisible in a screenshot and obvious
 * in a number.
 */
const day = (open: number, projected = false, openPoints = open * 2): RemainingDay => ({
  day: "2026-08-04",
  open,
  openPoints,
  projected,
});

describe("bars", () => {
  it("scales the tallest bar to full height and the rest against it", () => {
    expect(bars([day(10), day(5), day(0)]).map((bar) => bar.height)).toEqual([100, 50, 0]);
  });

  it("carries the projected flag through, because that is what gets hatched", () => {
    expect(bars([day(4), day(2, true)]).map((bar) => bar.projected)).toEqual([false, true]);
  });

  // A cycle where nothing has been closed yet is a flat line at the top, not a division by
  // zero and not an empty chart.
  it("draws a flat full-height line when every day is the same", () => {
    expect(bars([day(3), day(3)]).map((bar) => bar.height)).toEqual([100, 100]);
  });

  it("draws nothing at all rather than one bar of nothing", () => {
    expect(bars([])).toEqual([]);
  });

  // Zero open on every day is a finished cycle. The tallest bar is 0, and 0/0 has to come
  // out as an empty chart rather than as NaN heights the browser silently drops.
  it("survives a cycle with nothing left in it", () => {
    expect(bars([day(0), day(0)]).map((bar) => bar.height)).toEqual([0, 0]);
  });
});

describe("bars, in points", () => {
  // The same geometry over the other reading. Asserted separately because the unit is the
  // one thing a caller can get wrong here, and a chart drawn in rows under a header that
  // says points is a lie no test of the heights alone would catch.
  it("scales against the tallest day's points, not its tickets", () => {
    const remaining = [day(2, false, 13), day(2, false, 5), day(1, false, 0)];

    expect(bars(remaining, "points").map((bar) => bar.open)).toEqual([13, 5, 0]);
    expect(bars(remaining, "points").map((bar) => bar.height)).toEqual([100, (5 / 13) * 100, 0]);
  });

  // A team that estimates nothing has a points series of zeros. That has to draw an empty
  // chart — which is what tells the screen to fall back on the rows — and never NaN.
  it("comes out empty for a cycle nobody estimated, rather than as NaN", () => {
    expect(bars([day(4, false, 0), day(3, false, 0)], "points").map((bar) => bar.height)).toEqual([
      0, 0,
    ]);
  });

  it("still reads tickets when nothing asks for points", () => {
    expect(bars([day(4, false, 13)]).map((bar) => bar.open)).toEqual([4]);
  });
});

describe("progressSegments", () => {
  it("gives each status a width in proportion to its share of the cycle", () => {
    const segments = progressSegments({ done: 9, in_review: 3, in_progress: 5, todo: 7 }, 24);

    expect(segments.map((segment) => segment.status)).toEqual([
      "done",
      "in_review",
      "in_progress",
      "todo",
    ]);
    expect(segments[0].width).toBeCloseTo(37.5, 6);
    expect(segments[1].width).toBeCloseTo(12.5, 6);
    expect(segments[3].width).toBeCloseTo(29.1667, 3);
  });

  // The bar is one row of colour: the widths have to add to 100 or there is a gap at the
  // end that reads as work nobody accounted for.
  it("adds up to the whole bar", () => {
    const total = progressSegments({ done: 1, todo: 2 }, 3).reduce((sum, s) => sum + s.width, 0);
    expect(total).toBeCloseTo(100, 8);
  });

  // The drawing runs the bar done-first, left to right: what is finished is behind you.
  // Sorting by the status order the *list* uses would put the backlog on the left and read
  // as though the cycle ran backwards.
  it("puts what is finished on the left, whatever order the server sent", () => {
    const segments = progressSegments({ todo: 1, done: 1, backlog: 1 }, 3);
    expect(segments.map((segment) => segment.status)).toEqual(["done", "todo", "backlog"]);
  });

  it("leaves out a status nothing is in, so the legend has no dead entries", () => {
    expect(progressSegments({ done: 2, todo: 0 }, 2).map((s) => s.status)).toEqual(["done"]);
  });

  it("draws no bar for an empty cycle", () => {
    expect(progressSegments({}, 0)).toEqual([]);
  });
});

/**
 * The scaling rule on its own, which is what screen 40's delivered-points chart is drawn
 * with. Asserted here rather than there because it is the same arithmetic `bars` runs, and
 * two charts sharing one function is the whole point of not writing a second engine.
 */
describe("heights", () => {
  it("scales against the largest value, so a rise reads as a rise", () => {
    expect(heights([5, 9, 14])).toEqual([(5 / 14) * 100, (9 / 14) * 100, 100]);
  });

  // Somebody who has shipped nothing sized across six closed cycles. 0/0 must be a flat
  // empty chart and never NaN, which a browser drops silently — a chart that renders as
  // nothing at all with no error anywhere.
  it("comes out flat rather than NaN when every value is zero", () => {
    expect(heights([0, 0, 0])).toEqual([0, 0, 0]);
  });

  // `Math.max()` on no arguments is -Infinity, which would turn every height into a NaN.
  it("answers nothing for an empty series rather than dividing by minus infinity", () => {
    expect(heights([])).toEqual([]);
  });

  it("keeps a fractional value fractional, because a shared ticket splits its points", () => {
    expect(heights([4.5, 9])).toEqual([50, 100]);
  });
});

describe("progressSegments, over the load vocabulary", () => {
  // Not a restriction of the cycle bar's order: `LOAD_ORDER` puts `in_progress` before
  // `in_review` and `PROGRESS_ORDER` over the same four reverses them. Merging the two
  // would swap the first two segments of screen 40's load bar.
  it("puts what is in hand first, not what is furthest along", () => {
    const load = { in_review: 1, in_progress: 2, todo: 3, backlog: 4 };

    expect(progressSegments(load, 10, LOAD_BAR_ORDER).map((segment) => segment.status)).toEqual([
      "in_progress",
      "in_review",
      "todo",
      "backlog",
    ]);
    expect(progressSegments(load, 10).map((segment) => segment.status)).toEqual([
      "in_review",
      "in_progress",
      "todo",
      "backlog",
    ]);
  });

  // The load map arrives keyed by the open statuses only, so a settled ticket cannot be in
  // it — and the order must not invent a segment for one either.
  it("has no place for a settled status", () => {
    expect(LOAD_BAR_ORDER).not.toContain("done");
    expect(LOAD_BAR_ORDER).not.toContain("canceled");
  });

  it("still defaults to the cycle bar, so screen 19 reads as it always did", () => {
    expect(progressSegments({ done: 1, todo: 1 }, 2).map((segment) => segment.status)).toEqual([
      "done",
      "todo",
    ]);
  });
});
