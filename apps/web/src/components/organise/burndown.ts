import type { RemainingDay, TicketStatus } from "@/lib/api";
import { inOrder, isCounted, PROGRESS_ORDER, statusesWhere } from "@/lib/status-order";

/**
 * Screen 19's two charts, as geometry rather than as markup.
 *
 * The server sends counts; this turns them into percentages a `<span>` can be tall or
 * wide. Out here rather than in the component because a height that comes back wrong is
 * invisible in a screenshot and obvious in a number — the same argument
 * `timeline/bar-style.ts` already makes for the chart next door.
 */

export type Bar = {
  day: string;
  open: number;
  /** Percent of the tallest bar. */
  height: number;
  /** What gets hatched: a day nobody has measured. */
  projected: boolean;
};

/**
 * Which of the day's two readings the chart is drawn from.
 *
 * A parameter rather than a second function: the geometry is identical, and two copies of
 * it would be two places for the scaling to drift. Which one the screen asks for is a
 * property of the *cycle* — a team that estimates reads points, one that does not has
 * nothing but rows — and never of the chart.
 */
export type BurndownUnit = "tickets" | "points";

/**
 * One bar per day, scaled against the busiest day rather than against the cycle's total.
 * A cycle that opened at 24 and is down to 4 should read as a descent, and scaling against
 * a total nobody ever had would flatten it.
 *
 * `open` on the returned bar is whichever unit was asked for, so the label under a bar and
 * its height can never disagree about what they are counting.
 */
export function bars(remaining: readonly RemainingDay[], unit: BurndownUnit = "tickets"): Bar[] {
  if (remaining.length === 0) return [];
  const value = (day: RemainingDay) => (unit === "points" ? day.openPoints : day.open);
  const tallest = Math.max(...remaining.map(value));
  return remaining.map((day) => ({
    day: day.day,
    open: value(day),
    // A finished cycle is all zeros — and so is a cycle nobody estimated, read in points.
    // 0/0 has to come out as an empty chart rather than as NaN heights the browser
    // silently drops.
    height: tallest === 0 ? 0 : (value(day) / tallest) * 100,
    projected: day.projected,
  }));
}

export type ProgressSegment = { status: TicketStatus; count: number; width: number };

/**
 * The stacked progress bar, done-first.
 *
 * The drawing runs it left to right from `done`: what is finished is behind you. That is
 * the opposite of the order the *list* stacks its groups in, and deliberately so — one is
 * a history and the other is a queue. The same [PROGRESS_ORDER] the project page's bar
 * reads, because it is the same question asked of a different set of tickets.
 *
 * Which set is not this file's opinion. `CycleReport.byStatus` arrives keyed by
 * `CycleService.COUNTED_STATUSES` and `total` is counted over the same tickets, so a
 * `canceled` segment here would be a width over a denominator that never included it. The
 * membership is therefore read off the category, as the server reads it, rather than
 * spelled as five names that were right in 2026.
 */
const BAR_ORDER = inOrder(statusesWhere(isCounted), PROGRESS_ORDER);

export function progressSegments(
  byStatus: Record<string, number>,
  total: number,
): ProgressSegment[] {
  if (total <= 0) return [];
  return BAR_ORDER.flatMap((status) => {
    const count = byStatus[status] ?? 0;
    // A status nothing is in gets no segment, so the legend beneath has no dead entries.
    return count === 0 ? [] : [{ status, count, width: (count / total) * 100 }];
  });
}
