import type { RemainingDay, StatusBucket, StatusCategory, TicketStatus } from "@/lib/api";
import { inOrder, isCounted, isOpen, LOAD_ORDER, PROGRESS_ORDER } from "@/lib/status-order";

/**
 * The bar geometry these screens share, rather than as markup.
 *
 * The server sends counts; this turns them into percentages a `<span>` can be tall or
 * wide. Out here rather than in the component because a height that comes back wrong is
 * invisible in a screenshot and obvious in a number — the same argument
 * `timeline/bar-style.ts` already makes for the chart next door.
 *
 * Screen 19's burn-down came first and screen 40's delivered-points chart is drawn by the
 * same three functions. That is deliberate and it is what the personal-progress ticket
 * asked for in as many words: a second chart engine is two places for a scale to drift,
 * and the drift is invisible until two screens disagree about the same cycle. What the two
 * charts do not share is what they are *about* — a burn-down descends within one cycle and
 * a delivered series steps across several — so the difference lives in the callers and the
 * arithmetic lives here once.
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
  const tall = heights(remaining.map(value));
  return remaining.map((day, at) => ({
    day: day.day,
    open: value(day),
    height: tall[at],
    projected: day.projected,
  }));
}

/**
 * Each value as a percentage of the largest — the one line both bar charts stand on.
 *
 * Scaled against the busiest reading rather than against a total, because that is what
 * makes a *shape* visible: a burn-down from 24 down to 4 should read as a descent, and a
 * person who delivered 5, 9 and 14 points should read as a rise. Dividing by a total
 * nobody ever had would flatten both.
 *
 * A series of nothing but zeros is the case worth having a function for. A finished cycle,
 * a cycle nobody estimated read in points, and a person who has shipped nothing across
 * three closed cycles all arrive here as zeros, and `0/0` has to come out as a flat empty
 * chart rather than as the NaN heights a browser silently drops — which is a chart that
 * renders as nothing at all with no error anywhere. An empty series answers empty, so
 * `Math.max` is never called on no arguments and can never return `-Infinity`.
 */
export function heights(values: readonly number[]): number[] {
  const tallest = Math.max(0, ...values);
  return values.map((value) => (tallest === 0 ? 0 : (value / tallest) * 100));
}

export type ProgressSegment = {
  status: TicketStatus;
  /** The word above the segment, from the bucket — never looked up in a table of six. */
  label: string;
  category: StatusCategory;
  count: number;
  width: number;
};

/**
 * The stacked progress bar, done-first.
 *
 * The drawing runs it left to right from `done`: what is finished is behind you. That is
 * the opposite of the order the *list* stacks its groups in, and deliberately so — one is
 * a history and the other is a queue. The same [PROGRESS_ORDER] the project page's bar
 * reads, because it is the same question asked of a different set of tickets.
 *
 * Which set is not this file's opinion, and since `KAN-90` it is not this file's *guess*
 * either. It used to filter Kanso's six by category, which silently omitted any word a
 * team invented; the buckets now arrive on the payload, in the order the server grouped
 * them, and this only resequences them — `CycleReport.byStatus` is keyed by exactly those
 * and `total` is counted over the same tickets, so a `canceled` segment would be a width
 * over a denominator that never included it.
 */
export const barOrder = (buckets: readonly StatusBucket[]): StatusBucket[] =>
  bucketsInOrder(buckets.filter((bucket) => isCounted(bucket.category)), PROGRESS_ORDER);

/**
 * The same bar over the statuses a plate can be in, in the order a plate is read.
 *
 * Not a restriction of [barOrder] and not derivable from it — `LOAD_ORDER` puts
 * `in_progress` before `in_review`, which `PROGRESS_ORDER` over the same four statuses
 * reverses. Screen 40's load bar has no `done` at its left for a reader to measure a
 * descent against, so "furthest along first" answers nothing there and the leftmost
 * segment is the work actually in somebody's hands.
 */
export const loadBarOrder = (buckets: readonly StatusBucket[]): StatusBucket[] =>
  bucketsInOrder(buckets.filter((bucket) => isOpen(bucket.category)), LOAD_ORDER);

/**
 * [buckets], resequenced by [order] — `inOrder` over a bucket rather than a bare key.
 *
 * A bucket the order does not place sorts last rather than being dropped, which is the
 * whole payoff and now the common case: a team's invented word is in no constant here, so
 * it draws itself at the end of the bar, where it is visible and slightly wrong-looking
 * rather than absent. `inOrder`'s own docstring made that argument when the seventh status
 * was hypothetical.
 */
function bucketsInOrder(
  buckets: readonly StatusBucket[],
  order: readonly TicketStatus[],
): StatusBucket[] {
  const placed = inOrder(buckets.map((bucket) => bucket.key), order);
  return placed.map((key) => buckets.find((bucket) => bucket.key === key)!);
}

/**
 * [order] is the caller's and no longer defaulted, because since `KAN-90` there is no
 * order this file can supply: the buckets belong to the scope on screen, and the caller is
 * the only one holding it. Two screens ask this of two different vocabularies anyway —
 * see [loadBarOrder].
 */
export function progressSegments(
  byStatus: Record<string, number>,
  total: number,
  order: readonly StatusBucket[],
): ProgressSegment[] {
  if (total <= 0) return [];
  return order.flatMap((bucket) => {
    const count = byStatus[bucket.key] ?? 0;
    // A bucket nothing is in gets no segment, so the legend beneath has no dead entries.
    return count === 0
      ? []
      : [
          {
            status: bucket.key,
            label: bucket.label,
            category: bucket.category,
            count,
            width: (count / total) * 100,
          },
        ];
  });
}
