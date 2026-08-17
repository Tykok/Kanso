import type { RemainingDay, TicketStatus } from "@/lib/api";

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
 * One bar per day, scaled against the busiest day rather than against the cycle's total.
 * A cycle that opened at 24 and is down to 4 should read as a descent, and scaling against
 * a total nobody ever had would flatten it.
 */
export function bars(remaining: readonly RemainingDay[]): Bar[] {
  if (remaining.length === 0) return [];
  const tallest = Math.max(...remaining.map((day) => day.open));
  return remaining.map((day) => ({
    day: day.day,
    open: day.open,
    // A finished cycle is all zeros, and 0/0 has to come out as an empty chart rather than
    // as NaN heights the browser silently drops.
    height: tallest === 0 ? 0 : (day.open / tallest) * 100,
    projected: day.projected,
  }));
}

export type ProgressSegment = { status: TicketStatus; count: number; width: number };

/**
 * The stacked progress bar, done-first.
 *
 * The drawing runs it left to right from `done`: what is finished is behind you. That is
 * the opposite of the order the *list* stacks its groups in, and deliberately so — one is
 * a history and the other is a queue.
 */
const BAR_ORDER: TicketStatus[] = ["done", "in_review", "in_progress", "todo", "backlog"];

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
