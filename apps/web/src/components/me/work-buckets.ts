import {
  dayValue,
  fromDayValue,
  type KansoInstant,
  type Ticket,
  type TicketStatus,
  type TimelineDependency,
} from "@/lib/api";
import { categoryOf } from "@/lib/status";
import { isOpen } from "@/lib/status-order";
import { addDays } from "@/lib/timeline-geometry";

/**
 * The two rulings the Due and Blocked tabs are built on, as functions rather than as JSX.
 *
 * Both are decisions and not renderings — when a deadline has passed, and what counts as
 * being held up — so they live where they can be asserted, for the reason `lib/progress.ts`
 * and `lib/velocity.ts` both give: a decision inside a component is a decision nobody can
 * test. `/api/me/stats` already counts both of these server-side for the strip, and the
 * numbers here have to agree with that strip on the same screen, so what is below is a
 * deliberate transcription of `MyStatsService.overdue` and `MyStatsService.blocked` and
 * not a second opinion about either.
 */

export const DUE_BUCKETS = ["overdue", "thisWeek", "later"] as const;
export type DueBucket = (typeof DUE_BUCKETS)[number];

/**
 * The three readings of "now" the Due tab needs, gathered rather than read from a clock
 * inside — the same argument `MyStatsService.forPerson` makes for taking `now` as a
 * parameter: the bucket boundaries are all decided by it, and a function that read the
 * clock itself would measure something different every Monday and could not be pinned.
 */
export type DueClock = {
  /** The reader's own civil day, `YYYY-MM-DD` — `timeline-geometry.ts`'s `today()`. */
  day: string;
  /** The same moment in milliseconds, for a deadline that named an hour. */
  at: number;
  /**
   * The Monday this week begins on, taken from the last of `MyStats.weeks`.
   *
   * Read off the server's own answer and deliberately not computed here. The Done tab's
   * twelve buckets are ISO weeks decided in Postgres, and "this week" on the same screen
   * has to mean the same seven days or the two tabs disagree about what a week is — which
   * is the disagreement the spec forbids deriving a boundary in the browser to avoid.
   */
  weekStartsOn: string;
};

/**
 * Which of the three groups a ticket's deadline falls in, or nothing at all.
 *
 * `undefined` for a ticket with no date, and the Due tab draws no group for those: this
 * is the tab for work that has a deadline, and a fourth pile called "no date" would be
 * the Assigned tab again under a heading that promised dates.
 *
 * The overdue test is `MyStatsService.overdue` transcribed. A day-granularity date passes
 * at the *end* of its day — a ticket due "3 September" is not late at nine in the morning
 * on the third — while a date somebody gave a time to is late at that time, to the
 * minute. Rounding the second up to the end of the day would forgive an afternoon.
 *
 * Which is also why only the timed branch touches `Date`. A day must never be converted;
 * `dayValue` compares two `YYYY-MM-DD` strings, and ISO dates sort as text.
 */
export function dueBucketOf(
  due: KansoInstant | undefined,
  clock: DueClock,
): DueBucket | undefined {
  if (due === undefined) return undefined;
  const day = dayValue(due);
  if (due.hasTime ? Date.parse(due.at) < clock.at : day < clock.day) return "overdue";
  return day <= lastDayOfWeek(clock.weekStartsOn) ? "thisWeek" : "later";
}

/** The Sunday. Through `addDays`, which is the app's one implementation of "a day later". */
function lastDayOfWeek(monday: string): string {
  const start = fromDayValue(monday);
  return start ? dayValue(addDays(start, 6)) : monday;
}

/** What a predecessor has to say for itself, from whichever list managed to name it. */
export type Upstream = {
  id: string;
  identifier?: string;
  title: string;
  status: TicketStatus;
};

export type BlockedTicket = { ticket: Ticket; waitingOn: Upstream[] };

export type Blockages = {
  blocked: BlockedTicket[];
  /**
   * Mine with an incoming arrow whose far end nothing on the wire could name.
   *
   * A count and not a list, because a row that could not say what it is waiting for would
   * be a row with nothing on it. The tab prints it as a sentence beside the strip's own
   * `blocked`, so the gap between the two numbers is stated rather than hidden — see the
   * head of `blocked-tab.tsx` for why the gap exists at all.
   */
  unresolved: number;
};

/**
 * Mine that something upstream is still holding.
 *
 * `ticket_dependencies` is finish-to-start, so only the arrows pointing *into* a ticket
 * can block it: being somebody's predecessor is being waited on, not being blocked, and
 * counting both directions would report the person holding the queue up as the person
 * stuck in it. `MyStatsService.blocked` says the same thing about the same table.
 *
 * "Unfinished" is asked of the *category* and never of a list of names — `isOpen` is the
 * client half of the rule, and it puts `canceled` among the things that no longer hold
 * anybody, because a cancelled predecessor is a decision not to do the work and reading
 * it as a block would leave the successor waiting for something nobody will ever finish.
 * The day a seventh status means "finished", this follows without anybody remembering
 * that the line is here.
 */
export function blockages(
  open: readonly Ticket[],
  edges: readonly TimelineDependency[],
  upstream: ReadonlyMap<string, Upstream>,
): Blockages {
  const mine = new Set(open.map((ticket) => ticket.id));

  const incoming = new Map<string, string[]>();
  for (const edge of edges) {
    if (!mine.has(edge.successorId)) continue;
    const held = incoming.get(edge.successorId);
    if (held) held.push(edge.predecessorId);
    else incoming.set(edge.successorId, [edge.predecessorId]);
  }

  const blocked: BlockedTicket[] = [];
  let unresolved = 0;

  // In the order the rows arrived, which is the order the server sorted them in. Sorting
  // again here would put the Blocked tab in a different sequence from the Assigned tab
  // over the same rows, for no reason a reader could name.
  for (const ticket of open) {
    const predecessors = incoming.get(ticket.id);
    if (predecessors === undefined) continue;

    const named = predecessors.flatMap((id) => {
      const row = upstream.get(id);
      return row ? [row] : [];
    });
    const waitingOn = named.filter((row) => isOpen(categoryOf(row.status)));

    if (waitingOn.length > 0) blocked.push({ ticket, waitingOn });
    // Already listed above if anything known is holding it, so this only counts the rows
    // whose *only* answer is "cannot say" — a ticket blocked by one thing this read could
    // name and one it could not is on the list, not in the gap.
    else if (named.length < predecessors.length) unresolved += 1;
  }

  return { blocked, unresolved };
}
