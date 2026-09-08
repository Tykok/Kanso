import {
  dayValue,
  type KansoInstant,
  type ProjectHealth,
  type Team,
  type Ticket,
} from "@/lib/api";
import { PROJECT_HEALTH_LABELS, type StatusCategory } from "@/lib/status";
import { categoryOfTicket } from "@/lib/statuses";

/**
 * Everything screen 05 says in words, with no React in it.
 *
 * The project page is mostly arithmetic and copy — a proportion bar, a period, a health
 * label — and vitest runs under `environment: "node"`, so this is where those are proved.
 * `project-page.tsx` above it only draws.
 *
 * The feed's sentences were here too until `KAN-87`, and are now in `activity-copy.ts`.
 * They left because this file had reached 422 lines and because the feed had stopped being
 * screen 05's: `KAN-85` mounted it on the ticket panel as well, so the half that says what
 * happened is no longer a part of the half that says how a project is going.
 */

// --- the proportion bar ------------------------------------------------------

/**
 * One segment of a project's bar — keyed by **category** since `KAN-90`.
 *
 * The field is still called `status` because every reader of this type calls it that and
 * the value is what the segment is *about*; what changed is that it holds one of the five
 * meanings rather than one of six words. `KAN-9` gives a project no team in particular, so
 * its tickets can come from several teams with several vocabularies — the same reason the
 * app's other cross-team lists group this way.
 */
export type StatusCount = { status: StatusCategory; count: number };

/**
 * The order the bar is drawn in: finished, then in review, then under way, then not
 * started, then abandoned.
 *
 * Not `CATEGORY_ORDER`, which runs the other way. The bar answers one question — how much
 * of this is done — and the reader reads it left to right, so the answer has to start at
 * the left. The drawing does exactly this.
 *
 * Every category there is, and not a membership question at all: a project's bar is the
 * whole of its work, cancelled included, which is exactly what makes [donePercent] below a
 * different sum from this list.
 */
const BAR_ORDER: readonly StatusCategory[] = [
  "completed",
  "started",
  "unstarted",
  "backlog",
  "canceled",
];

/** All five, always, so a segment that empties leaves a gap rather than reordering the bar. */
export function statusCounts(teams: readonly Team[], tickets: Ticket[]): StatusCount[] {
  return BAR_ORDER.map((category) => ({
    status: category,
    count: tickets.filter(
      (ticket) => categoryOfTicket(teams, ticket.teamId, ticket.status) === category,
    ).length,
  }));
}

/**
 * What is finished, out of what still counts.
 *
 * A canceled ticket was abandoned: it was not delivered, and it is not outstanding
 * either. Leaving it in the denominator would make abandoning work look like falling
 * behind, and the whole point of the status is that the work stopped mattering.
 *
 * (The drawing prints `En cours · 62%` beside a legend that adds up to 4 done of 18,
 * which is 22% — the figure in the mock matches no ratio of its own numbers, so it is a
 * placeholder rather than a definition, and this is the definition.)
 */
export function donePercent(counts: StatusCount[]): number {
  const inCategory = (category: StatusCategory) =>
    counts
      .filter((entry) => entry.status === category)
      .reduce((total, entry) => total + entry.count, 0);
  const counting = counts
    .filter((entry) => entry.status !== "canceled")
    .reduce((total, entry) => total + entry.count, 0);
  // Rounded rather than truncated: 2 of 3 is two thirds done and printing 66 would be
  // the one place in the interface that rounds work *down*.
  return counting === 0 ? 0 : Math.round((inCategory("completed") / counting) * 100);
}

// --- the period --------------------------------------------------------------

/**
 * Exported for `activity-copy.ts`, which took the feed and needs the same twelve words.
 *
 * A table and not `Intl`, for [dayLabel]'s reason below: `Intl` would make every expected
 * string in two test files depend on the machine's locale.
 */
export const MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
];

/**
 * A floating day as `4 Aug`, sliced out of the ISO string.
 *
 * Never `new Date(instant.at).getDate()`. `hasTime: false` means the value names a *day*,
 * and a reader west of UTC would be shown the day before the one somebody posted — the
 * rule `dayValue` exists to enforce and the reason a month table beats `Intl` here, which
 * would also make the expected strings depend on the machine's locale.
 */
function dayLabel(instant: KansoInstant): string {
  const [, month, day] = dayValue(instant).split("-");
  return `${Number(day)} ${MONTHS[Number(month) - 1]}`;
}

/** The project's window, saying which bound it has when it has only one. */
export function periodLabel(
  start: KansoInstant | undefined,
  end: KansoInstant | undefined,
): string {
  if (start && end) return `${dayLabel(start)} → ${dayLabel(end)}`;
  if (start) return `from ${dayLabel(start)}`;
  if (end) return `until ${dayLabel(end)}`;
  // The row exists and has no answer, which is what the em dash says everywhere else.
  return "—";
}

// --- health ------------------------------------------------------------------

/**
 * What to print where a project's health goes, including when it has none.
 *
 * The absent case is the whole reason this is a function rather than an index into
 * `PROJECT_HEALTH_LABELS`. A project nobody has assessed answers `undefined`, and it must
 * not be drawn as "On track": "nobody has said" and "somebody looked and said it is fine"
 * are different facts, and rendering the first as the second turns every project in the
 * instance green on the day this ships — including the ones nobody has ever looked at.
 * A reader who learns that green is the resting state stops reading green, and then the
 * signal is worth nothing on the projects it was built for.
 *
 * "No update yet" and not "Unknown": the absence is a thing somebody can fix, and naming
 * the missing act says who has to fix it.
 */
export function healthLabel(health: ProjectHealth | undefined): string {
  return health ? PROJECT_HEALTH_LABELS[health] : "No update yet";
}
