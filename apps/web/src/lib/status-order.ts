import { DEFAULT_STATUSES, type TicketStatus } from "./api";
import { categoryOf, type StatusCategory } from "./status";

/**
 * Which statuses a drawing shows, and in what sequence — two questions, kept apart.
 *
 * Four screens used to answer both at once, each with one hand-written list: the grouped
 * list's `STATUS_ORDER`, the cycle bar's and the project bar's `BAR_ORDER`, the workload
 * chart's `PLOTTED`. A list like that is right only by coincidence. `PLOTTED` was exactly
 * the open statuses, but nothing said so — so the day a seventh status arrives open, the
 * chart stops drawing part of somebody's load and no test anywhere goes red.
 *
 * So: **membership is asked of `categoryOf`, and sequence stays written down.** They
 * cannot be folded into one another. Membership has to be derived, because "still open"
 * is a meaning and the category is where meanings live — the server already reads it this
 * way in `WorkloadService.OPEN_STATUSES` and `CycleService.COUNTED_STATUSES`, and the four
 * lists were the client failing to. Sequence cannot be derived, because no filter over the
 * category reproduces [LOAD_ORDER], and deriving the others would make an unrelated edit
 * silently restack a chart.
 *
 * The two halves live in one module rather than two because neither is usable alone: every
 * caller wants a membership *in* an order, and splitting them would put the halves of one
 * decision in two files.
 */

/**
 * Downwards as the work flows: what is waiting at the top, what is finished at the bottom.
 *
 * The order a grouped list stacks its buckets in — and the only one of the three that
 * crosses the wire. `TicketQueryRepository.statusRank` orders a grouped page in SQL and the
 * page boundary is cut against it, so this side may not restack what arrives; it has to
 * agree instead. See the note on agreement at the foot of this file.
 *
 * Written out rather than aliased to `DEFAULT_STATUSES`, which today happens to be spelled
 * the same way. The vocabulary is the set of statuses that exist; this is the sequence a
 * reader reads them in. Making the second the first would mean that reordering a literal
 * in `lib/api/core.ts` — an edit nobody would think twice about — restacks every grouped
 * view and moves every saved view's page boundary.
 */
export const WORKFLOW_ORDER: readonly TicketStatus[] = [
  "backlog",
  "todo",
  "in_progress",
  "in_review",
  "done",
  "canceled",
];

/**
 * How buckets stack when the scope holds more than one team — `KAN-28`.
 *
 * [WORKFLOW_ORDER] above used to be the one order that crossed the wire, and a team that
 * can reorder its own list ended that: a grouped page scoped to one team is stacked by
 * `team_statuses.position`, which arrives with the team. This is what took the job of the
 * two-sided constant, and it can hold it for the same reason the docstring at the head of
 * this file gives — the five categories are closed, while a team's words are not.
 *
 * `StatusOrder.CATEGORY_ORDER` in `domain/StatusOrder.kt` is the other copy, and
 * `StatusOrderTest.kt` pins the same sequence there: between them, the side that drifts
 * turns its own tests red.
 */
export const CATEGORY_ORDER: readonly StatusCategory[] = [
  "backlog",
  "unstarted",
  "started",
  "completed",
  "canceled",
];

/**
 * Finished, then in review, then under way, then not started, then abandoned.
 *
 * A proportion bar answers one question — how much of this is done — and the reader reads
 * it left to right, so the answer starts at the left. Both bars that ask it use this: the
 * cycle's on screen 19 and the project's on screen 05. They are one order over two
 * memberships, not two orders: the cycle bar is drawn from `CycleReport.byStatus`, which
 * the server keys by everything except `canceled`, and the project bar shows all six.
 */
export const PROGRESS_ORDER: readonly TicketStatus[] = [
  "done",
  "in_review",
  "in_progress",
  "todo",
  "backlog",
  "canceled",
];

/**
 * What is moving, first — and a genuinely different order from [PROGRESS_ORDER], not a
 * restriction of it.
 *
 * Screen 23's bar is open-only by construction, so it has no `done` at its left for a
 * reader to measure a descent against, and "furthest along first" answers nothing there.
 * Its leftmost segment is the work actually in somebody's hands. [PROGRESS_ORDER] over the
 * same four statuses would run `in_review, in_progress, todo, backlog`; merging the two
 * would swap the first two segments of every row on that screen.
 */
export const LOAD_ORDER: readonly TicketStatus[] = [
  "in_progress",
  "in_review",
  "todo",
  "backlog",
];

/**
 * The statuses that mean one of these things, read off the category.
 *
 * A predicate over the *category* rather than over the status, so a caller cannot quietly
 * name a status here and re-create the problem this module exists to remove.
 */
export const statusesWhere = (keep: (category: StatusCategory) => boolean): TicketStatus[] =>
  DEFAULT_STATUSES.filter((status) => keep(categoryOf(status)));

/**
 * Not settled: neither category that ends a ticket. The client half of
 * `WorkloadService.OPEN_STATUSES`, which is what `WorkloadRow.byStatus` is keyed by.
 */
export const isOpen = (category: StatusCategory): boolean =>
  category !== "completed" && category !== "canceled";

/**
 * Work that still counts as work. The client half of `CycleService.COUNTED_STATUSES`,
 * which is what `CycleReport.byStatus` is keyed by — a canceled ticket was abandoned, so
 * it is neither delivered nor outstanding and the cycle does not plot it at all.
 */
export const isCounted = (category: StatusCategory): boolean => category !== "canceled";

/**
 * [statuses], resequenced by [order].
 *
 * A status the order does not place sorts last rather than being dropped, and two of them
 * keep the sequence they arrived in. That is the whole payoff of deriving membership: a
 * seventh status appears on every chart it belongs on the day it is added, at the end,
 * where it is visible and wrong-looking enough that somebody places it properly — instead
 * of not appearing at all, which is invisible.
 */
export function inOrder(
  statuses: readonly TicketStatus[],
  order: readonly TicketStatus[],
): TicketStatus[] {
  const rank = (status: TicketStatus) => {
    const at = order.indexOf(status);
    return at === -1 ? order.length : at;
  };
  // `sort` on the copy is stable, which is what keeps two unplaced statuses in the order
  // the caller handed them over — `DEFAULT_STATUSES` order, wherever the caller derived them.
  return [...statuses].sort((left, right) => rank(left) - rank(right));
}
