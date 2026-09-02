/**
 * `/me`'s one request: the caller's own numbers.
 *
 * The wire shapes come straight from `dev.kanso.service.MyStatsService`, which puts its
 * own types on the wire with no response DTO between them — so what is written here is
 * that file's declarations transcribed, and the only translations are the two Jackson
 * makes: `java.time` values are ISO-8601 strings, and a null property is *omitted*, which
 * is why every absent field below is optional rather than `| null`. `Ticket.identifier`
 * in `core.ts` argues that one at length: a `=== null` test would silently miss every
 * absent value.
 *
 * There is one endpoint and not five. The four strip counts, the twelve bars, the
 * commitment and the unsized count are read off the same rows by the same server pass, so
 * splitting them into requests would let two of the numbers on one screen come from two
 * different moments — which is how a strip ends up disagreeing with the chart it sits on
 * top of.
 */

import { request, type EffortPoints } from "./core";

/**
 * The four numbers above the tabs, each of which is a tab.
 *
 * All four count *rows*, not points: a ticket two people share is on both their plates
 * whole, which is the same ruling the workload chart makes about the same join table. The
 * points in {@link MyFinishedWeek} are split instead, and for the opposite reason.
 *
 * A zero here is a measurement and must be drawn as one. `inbox/tabs.tsx` already makes
 * the argument the `/me` strip inherits: a red zero is an alarm about nothing, so a zero
 * `overdue` prints in faint ink and never in `--urgent`.
 */
export type MyWorkStrip = {
  open: number;
  overdue: number;
  blocked: number;
  /** The last of {@link MyStats.weeks}, so the strip and the chart cannot disagree. */
  finishedThisWeek: number;
};

/**
 * One bar of the Done chart — one ISO week of what the caller finished.
 *
 * The week is named three ways and none of them is computed here. That is the whole point
 * of the field: an ISO week boundary decided from the browser's clock and a count decided
 * in Postgres disagree twice a year, and the disagreement shows up as a bar in the wrong
 * place, which no screenshot reveals.
 *
 * [points] is fractional, because a ticket with two assignees contributes half of itself
 * to each — the server's `MyFinishedWeek` explains why, and it is the same rule
 * `lib/velocity.ts` reads. [unestimated] is the part the bar cannot speak for: an unsized
 * ticket is counted in [finished] and absent from [points], never added as a zero.
 *
 * Every one of the twelve weeks is present, including the ones nothing closed in. A
 * missing week and a quiet week are different facts, and `done-bars.ts` gets to draw the
 * quiet one.
 */
export type MyFinishedWeek = {
  isoYear: number;
  isoWeek: number;
  /** `2026-08-31` — the Monday the ISO week begins on, and the axis label's source. */
  startsOn: string;
  finished: number;
  points: number;
  unestimated: number;
};

/**
 * What the caller committed to in one cycle in progress, against what they have closed.
 *
 * The only figure on this screen that can be read *during* a cycle: a velocity is
 * measured over closed cycles by construction, so somebody three days into a fortnight
 * has nothing else to look at. It sits at the head of the Done tab for that reason.
 *
 * One row per team, never a total. A cycle is one team's calendar with one team's dates,
 * and summing a person's commitment in two teams would produce a number measured against
 * two fortnights at once. A caller in one team gets one row; a caller with no cycle in
 * progress gets none, which is an absent figure rather than a zero one.
 */
export type MyCommitment = {
  teamId: string;
  teamName: string;
  /** `KAN` — what a header prints beside the cycle number. */
  teamKey: string;
  cycleId: string;
  cycleNumber: number;
  startsOn: string;
  endsOn: string;
  /** Their tickets in the cycle, counted whole. */
  committed: number;
  finished: number;
  committedPoints: number;
  finishedPoints: number;
  /** How many of [committed] nobody sized — absent from both point totals. */
  unestimated: number;
};

/**
 * A finished ticket, as the Done tab lists it.
 *
 * [completedAt] lives here and deliberately not on the shared `Ticket` row.
 * Widening the row every list in the app already fetches, to serve one chart, is how a
 * DTO becomes a junk drawer — so one screen needs the date and one response carries it.
 */
export type MyFinishedTicket = {
  id: string;
  /** `KAN-142`. Absent only for a ticket whose team went away underneath the read. */
  identifier?: string;
  title: string;
  completedAt: string;
  /** Absent means nobody sized it — never 0, which would be a real estimate. */
  estimate?: EffortPoints;
};

/**
 * Everything `/me` draws about the caller.
 *
 * [openUnestimated] is at the head of the Velocity tab rather than in the strip, because
 * it is not a count of work — it is the reason a measured velocity understates, so it
 * belongs beside the number it explains and nowhere else. Open work only: it is too late
 * to size a ticket that has already closed.
 */
export type MyStats = {
  strip: MyWorkStrip;
  /** Twelve, oldest first — the order the chart draws in. */
  weeks: MyFinishedWeek[];
  commitments: MyCommitment[];
  openUnestimated: number;
  /** Newest completion first, and the head of the same list the bars are drawn from. */
  recentlyFinished: MyFinishedTicket[];
};

export const meStatsApi = {
  /**
   * No parameters, and no `userId` to pass. The route answers about the caller and only
   * the caller — `MyStatsController` reuses the rule `/api/me/velocity` settled on, "you
   * may read your own" — and it takes no `teamId` either, since counts and point sums add
   * across teams where a rate cannot.
   */
  stats: () => request<MyStats>("/api/me/stats"),
};
