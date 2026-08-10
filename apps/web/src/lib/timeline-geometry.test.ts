import { describe, expect, test } from "vitest";
import {
  addDays,
  axisTicks,
  boundLabel,
  dayKey,
  instantAtX,
  laterBy,
  PX_PER_DAY,
  snapDays,
  today,
  widthOf,
  xOf,
} from "./timeline-geometry";

const floating = (day: string) => ({ at: `${day}T00:00:00Z`, hasTime: false });
const timed = (iso: string) => ({ at: iso, hasTime: true });

describe("a day is never converted", () => {
  test("a floating bound reads the same in Tokyo and in Los Angeles", () => {
    const bound = floating("2026-08-12");
    expect(boundLabel(bound, "Asia/Tokyo")).toBe(boundLabel(bound, "America/Los_Angeles"));
    expect(boundLabel(bound, "America/Los_Angeles")).toContain("12");
  });

  test("a timed bound is converted, because it names a moment", () => {
    const bound = timed("2026-08-12T23:00:00Z");
    expect(boundLabel(bound, "Asia/Tokyo")).not.toBe(boundLabel(bound, "America/Los_Angeles"));
  });

  test("a floating bound lands on its own day column whatever the reader's zone", () => {
    // The trap: a Date-based implementation puts 2026-08-12T00:00Z one column left
    // for anyone west of UTC.
    expect(dayKey(floating("2026-08-12"))).toBe("2026-08-12");
  });
});

describe("placing a bar", () => {
  test("x is the number of days from the origin, times the zoom", () => {
    expect(xOf(floating("2026-08-04"), "2026-08-01", "day")).toBe(3 * PX_PER_DAY.day);
    expect(xOf(floating("2026-08-04"), "2026-08-01", "month")).toBe(3 * PX_PER_DAY.month);
  });

  test("a bar is at least one zoom unit wide, so a milestone is still visible", () => {
    const day = floating("2026-08-04");
    expect(widthOf(day, day, "day")).toBe(PX_PER_DAY.day);
  });

  test("width spans the whole last day rather than stopping at its start", () => {
    // A ticket from the 4th to the 6th occupies three columns, not two.
    expect(widthOf(floating("2026-08-04"), floating("2026-08-06"), "day")).toBe(
      3 * PX_PER_DAY.day,
    );
  });

  test("an inverted pair still renders one column rather than a negative bar", () => {
    // The API refuses a due before a start, but a drag can hold one for a frame.
    expect(widthOf(floating("2026-08-06"), floating("2026-08-04"), "day")).toBe(PX_PER_DAY.day);
  });

  test("x and instantAtX are inverses on a column boundary", () => {
    const x = xOf(floating("2026-08-09"), "2026-08-01", "week");
    expect(instantAtX(x, "2026-08-01", "week")).toEqual(floating("2026-08-09"));
  });

  test("a dropped bar is a floating day, never a moment", () => {
    expect(instantAtX(17, "2026-08-01", "day").hasTime).toBe(false);
  });
});

describe("dragging", () => {
  test("a pixel delta snaps to whole days", () => {
    expect(snapDays(PX_PER_DAY.day * 2 + 3, "day")).toBe(2);
    expect(snapDays(-PX_PER_DAY.day * 2 - 3, "day")).toBe(-2);
  });

  test("a sub-unit drag snaps to nothing rather than to a fraction", () => {
    expect(snapDays(2, "month")).toBe(0);
  });

  test("addDays crosses a month boundary without a timezone in sight", () => {
    expect(addDays(floating("2026-08-30"), 3)).toEqual(floating("2026-09-02"));
  });

  test("addDays leaves a timed bound floating on its own day, and does not shift it", () => {
    // A bar is dragged by columns, so what comes back names a day. Reading the hour
    // off `at` and re-adding it would convert, which is the one thing this module
    // refuses to do.
    expect(addDays(timed("2026-08-30T23:00:00Z"), 3)).toEqual(floating("2026-09-02"));
  });

  test("laterBy moves a floating bound exactly as addDays does", () => {
    expect(laterBy(floating("2026-08-30"), 3)).toEqual(floating("2026-09-02"));
  });

  test("laterBy keeps the hour a timed bound arrived with", () => {
    // The other half of the rule above: a bar is dragged by columns, but a deadline at
    // 17:30 must not become a whole day because somebody moved it a week out.
    expect(laterBy(timed("2026-08-30T17:30:00Z"), 3)).toEqual(timed("2026-09-02T17:30:00Z"));
  });

  test("laterBy moves a timed bound backwards across a month boundary too", () => {
    expect(laterBy(timed("2026-09-02T17:30:00Z"), -3)).toEqual(timed("2026-08-30T17:30:00Z"));
  });
});

describe("today", () => {
  test("is the reader's own civil day, not UTC's", () => {
    // Checked against a formatter rather than against the same arithmetic: `sv-SE`
    // spells a local date as `YYYY-MM-DD`, so the two implementations can only agree
    // by both meaning the local day. `toISOString().slice(0, 10)` — the trap — names
    // tomorrow west of Greenwich all evening, and would fail this in that zone.
    expect(dayKey(today())).toBe(new Date().toLocaleDateString("sv-SE"));
  });

  test("names a day rather than a moment", () => {
    expect(today().hasTime).toBe(false);
    expect(today().at).toMatch(/^\d{4}-\d{2}-\d{2}T00:00:00Z$/);
  });
});

describe("the axis", () => {
  test("day zoom labels every day", () => {
    const ticks = axisTicks("2026-08-01", 3, "day");
    expect(ticks.map((tick) => tick.day)).toEqual(["2026-08-01", "2026-08-02", "2026-08-03"]);
  });

  test("week zoom labels Mondays only", () => {
    // 2026-08-03 is the first Monday on or after 2026-08-01.
    const ticks = axisTicks("2026-08-01", 10, "week");
    expect(ticks.map((tick) => tick.day)).toEqual(["2026-08-03", "2026-08-10"]);
    expect(ticks[0].x).toBe(2 * PX_PER_DAY.week);
  });

  test("month zoom labels first-of-month only", () => {
    const ticks = axisTicks("2026-08-30", 5, "month");
    expect(ticks.map((tick) => tick.day)).toEqual(["2026-09-01"]);
  });
});
