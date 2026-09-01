import { dayValue, type ActivityRow, type KansoInstant, type Ticket, type TicketStatus } from "@/lib/api";
import { categoryOf, STATUS_LABELS, type StatusCategory } from "@/lib/status";

/**
 * Everything screen 05 says in words, with no React in it.
 *
 * The project page is mostly arithmetic and copy — a proportion bar, a period, a feed of
 * sentences — and vitest runs under `environment: "node"`, so this is where those are
 * proved. `project-page.tsx` above it only draws.
 */

// --- the proportion bar ------------------------------------------------------

export type StatusCount = { status: TicketStatus; count: number };

/**
 * The order the bar is drawn in: finished, then in review, then under way, then not
 * started, then abandoned.
 *
 * Not `TICKET_STATUSES` order, which runs the other way. The bar answers one question —
 * how much of this is done — and the reader reads it left to right, so the answer has to
 * start at the left. The drawing does exactly this.
 */
const BAR_ORDER: readonly TicketStatus[] = [
  "done",
  "in_review",
  "in_progress",
  "todo",
  "backlog",
  "canceled",
];

/** All six, always, so a segment that empties leaves a gap rather than reordering the bar. */
export function statusCounts(tickets: Ticket[]): StatusCount[] {
  return BAR_ORDER.map((status) => ({
    status,
    count: tickets.filter((ticket) => ticket.status === status).length,
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
      .filter((entry) => categoryOf(entry.status) === category)
      .reduce((total, entry) => total + entry.count, 0);
  const counting = counts
    .filter((entry) => categoryOf(entry.status) !== "canceled")
    .reduce((total, entry) => total + entry.count, 0);
  // Rounded rather than truncated: 2 of 3 is two thirds done and printing 66 would be
  // the one place in the interface that rounds work *down*.
  return counting === 0 ? 0 : Math.round((inCategory("completed") / counting) * 100);
}

// --- the period --------------------------------------------------------------

const MONTHS = [
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

// --- the feed ----------------------------------------------------------------

/** What a row's `payload` may name, read by key and never assumed to be there. */
const ref = (payload: Record<string, unknown>): string | undefined =>
  typeof payload.ref === "string" ? payload.ref : undefined;

const statusOf = (payload: Record<string, unknown>, key: "from" | "to"): string | undefined => {
  const value = payload[key];
  return typeof value === "string" && value in STATUS_LABELS
    ? STATUS_LABELS[value as TicketStatus]
    : undefined;
};

/**
 * One row of the feed, as a sentence.
 *
 * The drawing gives three: "Tykok a passé KAN-142 en cours", "2 pages poussées vers
 * Notion", "Léa a terminé KAN-131". Two name a person and one does not, which is exactly
 * the shape `actor: null` has — `activity.actor_id` is `ON DELETE SET NULL`, and the
 * mirror is not a person at all.
 *
 * `payload` carries the before and after of a scalar change and nothing else, so every
 * branch below has to read as a sentence with an empty payload too: a row that arrived
 * from a service that recorded less than this one hoped for must not print `undefined`.
 */
export function activitySentence(row: ActivityRow): string {
  const who = row.actor?.displayName;
  const what = ref(row.payload);

  // Written as the verb phrase first, so the actor is prepended once rather than in
  // twelve branches that could each get the spacing wrong.
  const phrase = ((): string => {
    switch (row.kind) {
      case "created":
        return what ? `created ${what}` : "created a ticket";
      case "status_changed": {
        const to = statusOf(row.payload, "to");
        if (what && to) return `moved ${what} to ${to}`;
        if (to) return `moved a ticket to ${to}`;
        return "changed a status";
      }
      case "priority_changed":
        return what ? `reprioritised ${what}` : "changed a priority";
      case "assigned":
        return what ? `took ${what}` : "took a ticket";
      case "unassigned":
        return what ? `let go of ${what}` : "let go of a ticket";
      case "renamed":
        return what ? `renamed ${what}` : "renamed a ticket";
      case "scheduled":
        return what ? `scheduled ${what}` : "scheduled a ticket";
      case "archived":
        return what ? `archived ${what}` : "archived a ticket";
      case "commented":
        return what ? `commented on ${what}` : "left a comment";
      case "labelled":
        return what ? `labelled ${what}` : "labelled a ticket";
      case "mirror_pushed":
        return "pushed to Notion";
      case "carried_over": {
        // The only branch that names a *cycle* rather than a field of the ticket, and
        // the only one whose "to" is a number: closing a cycle moves what did not fit,
        // and the sentence has to say where it went or the reader has to go looking.
        const to = typeof row.payload.to === "number" ? row.payload.to : undefined;
        if (what && to) return `carried ${what} into cycle ${to}`;
        if (to) return `carried unfinished work into cycle ${to}`;
        return what ? `carried ${what} into the next cycle` : "carried work into the next cycle";
      }
    }
  })();

  // No actor: the phrase becomes the sentence, capitalised, because "nobody pushed to
  // Notion" is not what happened.
  return who ? `${who} ${phrase}` : phrase[0].toUpperCase() + phrase.slice(1);
}

/**
 * When it happened, at the resolution the reader needs.
 *
 * The hour today, the word "yesterday" for the day before, the day itself for anything
 * older — the drawing's own three resolutions. Unlike a project bound, an activity row is
 * a *moment*, so it is converted into the reader's zone: 23:30 UTC is already tomorrow in
 * Paris, and a feed saying "yesterday" about something done an hour ago would be wrong.
 *
 * [now] and [timeZone] are arguments rather than read from the environment: a function
 * that asks the clock passes at 14:20 and fails at midnight.
 */
export function activityTime(createdAt: string, now: Date, timeZone: string): string {
  const parts = (date: Date) =>
    new Intl.DateTimeFormat("en-GB", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    })
      .format(date)
      // en-GB gives `17/08/2026, 14:20`, which splits cleanly on the punctuation.
      .split(/[/,: ]+/);

  const at = parts(new Date(createdAt));
  const today = parts(now);
  const sameDay = at[0] === today[0] && at[1] === today[1] && at[2] === today[2];
  if (sameDay) return `${at[3]}:${at[4]}`;

  // A day earlier in the reader's zone, computed by asking the formatter about "24 hours
  // ago" rather than by subtracting from the calendar — a month boundary is not a
  // subtraction and neither is a daylight-saving change.
  const yesterday = parts(new Date(now.getTime() - 24 * 60 * 60 * 1000));
  if (at[0] === yesterday[0] && at[1] === yesterday[1] && at[2] === yesterday[2]) {
    return "yesterday";
  }

  return `${Number(at[0])} ${MONTHS[Number(at[1]) - 1]}`;
}
