import type { MyFinishedWeek } from "@/lib/api";

/**
 * The Done tab's twelve bars, as geometry rather than as markup.
 *
 * The server sends twelve buckets; this turns them into percentages a `<span>` can be
 * tall. Out here rather than in the component for the reason `burndown.ts` gives about
 * the chart next door: a height that comes back wrong is invisible in a screenshot and
 * obvious in a number, so the arithmetic is asserted in `done-bars.test.ts` and the
 * markup is left dumb.
 *
 * Nothing here decides what a week *is*. `MyFinishedWeek` arrives with its ISO year, its
 * ISO week and the Monday it starts on, all three computed in Postgres, and this file
 * only reads them — the spec forbids deriving a week boundary in the browser, because a
 * boundary from a local clock and a count from a query disagree twice a year and the
 * disagreement shows up as a bar in the wrong place.
 *
 * And nothing here recounts anything either. `MyStats.strip.finishedThisWeek` *is* the
 * last bucket's `finished`; the strip reads it off the strip and the chart reads it off
 * the buckets, and neither adds up a second opinion.
 */

/**
 * Which of a week's two readings the chart is drawn from.
 *
 * A parameter rather than a second function, exactly as `BurndownUnit` is: the geometry
 * is identical and two copies of it would be two places for the scaling to drift. The
 * two readings are genuinely different claims, though, and the difference is the whole
 * reason the toggle exists — [MyFinishedWeek.finished] counts rows whole, and
 * [MyFinishedWeek.points] is a *share*, halved for a ticket two people own, and silent
 * about every ticket nobody sized. So a bar drawn in points cannot speak for the work
 * `unestimated` counts, and the caption beside it has to say so.
 */
export type DoneUnit = "tickets" | "points";

export type DoneBar = {
  isoYear: number;
  isoWeek: number;
  /** The Monday, `2026-08-31` — the axis label's source, never re-derived. */
  startsOn: string;
  /** Whichever unit was asked for, so a bar's height and its number agree. */
  value: number;
  /** Percent of the tallest week in the window. */
  height: number;
  finished: number;
  points: number;
  unestimated: number;
  /** `W36`. */
  label: string;
  /**
   * The last of the twelve — the week the reader is standing in.
   *
   * Marked rather than dropped, and marked rather than left to look finished. A week
   * three days old is not comparable to eleven whole ones, so the chart draws it in a
   * quieter fill; hiding it would be worse, because "what have I closed this week" is the
   * number the strip above puts first and a chart that omitted it would contradict it.
   */
  current: boolean;
};

/**
 * One bar per week, scaled against the busiest week rather than against the total.
 *
 * The same argument `burndown.ts` makes about the same choice: a run of weeks that goes
 * 2, 3, 9 has to read as a rise, and scaling against a sum nobody ever delivered in one
 * week would flatten every bar into the same stub.
 *
 * A quiet week keeps its bucket at height zero. A missing week and a week nothing closed
 * in are different facts — the server sends all twelve, zeros included, precisely so the
 * quiet one can be drawn — and compressing the quiet ones out would tighten a twelve-week
 * series into a flattering line.
 */
export function doneBars(weeks: readonly MyFinishedWeek[], unit: DoneUnit = "tickets"): DoneBar[] {
  if (weeks.length === 0) return [];
  const value = (week: MyFinishedWeek) => (unit === "points" ? week.points : week.finished);
  const tallest = Math.max(...weeks.map(value));
  const last = weeks.length - 1;

  return weeks.map((week, at) => ({
    isoYear: week.isoYear,
    isoWeek: week.isoWeek,
    startsOn: week.startsOn,
    value: value(week),
    // Twelve quiet weeks — a new account, or a quarter read in points by somebody who
    // does not estimate — is 0/0, and it has to come out as an empty chart rather than as
    // NaN heights the browser silently drops.
    height: tallest === 0 ? 0 : (value(week) / tallest) * 100,
    finished: week.finished,
    points: week.points,
    unestimated: week.unestimated,
    label: `W${week.isoWeek}`,
    current: at === last,
  }));
}

/**
 * What the twelve weeks add up to, and what the sum cannot speak for.
 *
 * [unsizedShare] is null and never zero when nothing closed at all. Zero would read as
 * "everything you finished was sized", which is a compliment about an empty quarter —
 * `VelocityService.perWorkingDay` is the house rule this follows: zero is a measurement,
 * null is the absence of one.
 */
export type DoneTotals = {
  finished: number;
  points: number;
  unestimated: number;
  /** `0`–`1`, the fraction of finished work no estimate could speak for. */
  unsizedShare: number | null;
};

export function doneTotals(weeks: readonly MyFinishedWeek[]): DoneTotals {
  const finished = weeks.reduce((sum, week) => sum + week.finished, 0);
  const unestimated = weeks.reduce((sum, week) => sum + week.unestimated, 0);
  return {
    finished,
    // Fractional by construction: a ticket two people closed is half a delivery each.
    points: weeks.reduce((sum, week) => sum + week.points, 0),
    unestimated,
    unsizedShare: finished === 0 ? null : unestimated / finished,
  };
}
