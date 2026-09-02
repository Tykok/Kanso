import { describe, expect, it } from "vitest";
import type { MyFinishedWeek } from "@/lib/api";
import { doneBars, doneTotals } from "./done-bars";

/**
 * The Done tab's twelve bars, as geometry.
 *
 * The server sends twelve weekly buckets, oldest first, zeros included; this turns them
 * into percentages a `<span>` can be tall. Asserted here for the reason
 * `burndown.test.ts` states about the chart next door: a height that comes back wrong is
 * invisible in a screenshot and obvious in a number.
 */
const week = (
  isoWeek: number,
  finished: number,
  points = finished * 2,
  unestimated = 0,
): MyFinishedWeek => ({
  isoYear: 2026,
  isoWeek,
  // A real Monday, and never re-derived: the day the server named is the day the axis
  // prints, which is the whole reason the field is on the wire.
  startsOn: `2026-06-${String(isoWeek).padStart(2, "0")}`,
  finished,
  points,
  unestimated,
});

describe("doneBars", () => {
  it("scales the tallest week to full height and the rest against it", () => {
    expect(doneBars([week(1, 8), week(2, 4), week(3, 2)]).map((bar) => bar.height)).toEqual([
      100, 50, 25,
    ]);
  });

  // The scale is the busiest *week*, not the quarter's total. A run of 2, 3, 9 has to read
  // as a rise; against a total of 14 every bar would be a stub and the shape would be gone.
  it("scales against the busiest week and not against the total", () => {
    const bars = doneBars([week(1, 2), week(2, 3), week(3, 9)]);

    expect(bars.map((bar) => bar.height)).toEqual([(2 / 9) * 100, (3 / 9) * 100, 100]);
  });

  // A week nothing closed in is a bucket of zeros on the wire and a bar of nothing on
  // screen. It keeps its place in the series: dropping it would tighten twelve weeks into
  // eleven and flatter the line.
  it("keeps a quiet week in the series, at no height", () => {
    const bars = doneBars([week(1, 5), week(2, 0), week(3, 3)]);

    expect(bars).toHaveLength(3);
    expect(bars[1].height).toBe(0);
    expect(bars[1].isoWeek).toBe(2);
    expect(bars[1].label).toBe("W2");
  });

  // Twelve quiet weeks — a new account — is 0/0. It has to come out as an empty chart
  // rather than as NaN heights the browser silently drops.
  it("survives a window in which nothing was finished at all", () => {
    expect(doneBars([week(1, 0), week(2, 0)]).map((bar) => bar.height)).toEqual([0, 0]);
  });

  it("draws nothing at all rather than one bar of nothing", () => {
    expect(doneBars([])).toEqual([]);
  });

  // The last of the twelve is the week the reader is standing in. Three days of it are not
  // comparable to eleven whole weeks, so it is marked rather than left to look finished —
  // and marked rather than hidden, since the strip above already prints its number.
  it("marks the last week as the one in progress, and only that one", () => {
    expect(doneBars([week(1, 3), week(2, 4), week(3, 1)]).map((bar) => bar.current)).toEqual([
      false,
      false,
      true,
    ]);
  });

  it("carries the week's own names through rather than deriving them", () => {
    const [bar] = doneBars([week(9, 2)]);

    expect(bar.isoYear).toBe(2026);
    expect(bar.isoWeek).toBe(9);
    expect(bar.startsOn).toBe("2026-06-09");
  });
});

describe("doneBars, in points", () => {
  // The same geometry over the other reading, asserted separately because the unit is the
  // one thing a caller can get wrong here: a chart drawn in rows under a header that says
  // points is a lie no test of the heights alone would catch.
  it("scales against the week with the most points, not the most tickets", () => {
    const weeks = [week(1, 2, 13), week(2, 5, 5), week(3, 1, 0)];

    expect(doneBars(weeks, "points").map((bar) => bar.value)).toEqual([13, 5, 0]);
    expect(doneBars(weeks, "points").map((bar) => bar.height)).toEqual([100, (5 / 13) * 100, 0]);
  });

  // Points are a share, so a ticket two people closed is half a delivery each. The bar
  // has to be able to be 1.5 tall without rounding to a whole delivery nobody made.
  it("keeps a fractional share fractional", () => {
    expect(doneBars([week(1, 3, 1.5), week(2, 1, 3)], "points").map((bar) => bar.value)).toEqual([
      1.5, 3,
    ]);
  });

  // Somebody who finished work nobody sized has a points series of zeros over a finished
  // series that is not. That draws an empty chart — which is what tells the screen to fall
  // back on the rows — and never NaN.
  it("comes out empty for a quarter nobody estimated, rather than as NaN", () => {
    const weeks = [week(1, 4, 0, 4), week(2, 3, 0, 3)];

    expect(doneBars(weeks, "points").map((bar) => bar.height)).toEqual([0, 0]);
    expect(doneBars(weeks, "tickets").map((bar) => bar.height)).toEqual([100, 75]);
  });
});

describe("doneTotals", () => {
  it("adds the twelve weeks up, in both readings", () => {
    const totals = doneTotals([week(1, 3, 5.5, 1), week(2, 0, 0, 0), week(3, 2, 4, 0)]);

    expect(totals.finished).toBe(5);
    expect(totals.points).toBe(9.5);
    expect(totals.unestimated).toBe(1);
  });

  it("reports the unsized share as a fraction of what was finished", () => {
    expect(doneTotals([week(1, 4, 4, 1), week(2, 4, 4, 1)]).unsizedShare).toBe(2 / 8);
  });

  // Null, not zero. Zero would read as "everything you finished was sized", which is a
  // compliment about an empty quarter — `VelocityService.perWorkingDay` is the house rule:
  // zero is a measurement, null is the absence of one.
  it("has no unsized share at all when nothing was finished", () => {
    expect(doneTotals([week(1, 0), week(2, 0)]).unsizedShare).toBeNull();
    expect(doneTotals([]).unsizedShare).toBeNull();
  });

  it("reports a share of zero when everything finished was sized", () => {
    expect(doneTotals([week(1, 3, 6, 0)]).unsizedShare).toBe(0);
  });
});
