import type { TicketTime, TimeEntry } from "@/lib/api";

/**
 * Worked minutes, in words. **Deliberately not `duration()` from `lib/insights.ts`.**
 *
 * That function is right for what it measures and wrong for this, and the difference is the
 * whole reason this one exists. It turns anything past 48 hours into calendar days — `2.1
 * days` — which is exactly correct for a *cycle time*, because a ticket sitting in review
 * really did sit there through two whole days and the weekend really did happen to whoever
 * was waiting. Fifty **worked** hours are not 2.1 days of anything: nobody works round the
 * clock, and "2.1 days" invites the reader to divide by a working day that this product
 * refuses to guess at — `CycleTimeService` and `VelocityService` both say why, and KAN-23
 * turned working days down for want of the per-person calendar.
 *
 * So the ceiling is hours, for ever. `125h 30m` and never `5.2 days`, which is also what an
 * invoice says. Reusing `duration()` here would have been the tidier import and would have
 * put a unit on the screen that means something different from what it says — the same
 * family of bug as the trend axis that mixed `7.6 days` with `45 hours` and got the ranking
 * backwards.
 *
 * `0m` is a real answer and not an empty one: a stopwatch pressed twice inside half a minute
 * honestly measured almost nothing, and the row exists so the person can see it and delete
 * it. Absent hours and absent minutes are dropped rather than printed as `0h 45m` / `2h 0m`.
 */
export function workedTime(minutes: number): string {
  if (minutes <= 0) return "0m";
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return `${rest}m`;
  if (rest === 0) return `${hours}h`;
  return `${hours}h ${rest}m`;
}

/**
 * The clock's reading right now, from the instant it started.
 *
 * Computed on the client rather than read off `elapsedMinutes`, and only for a running row.
 * The server's figure was true when it answered and a page left open for twenty minutes
 * would go on showing it — a clock that does not move is worse than no clock, because it
 * reads as a stopped one. The settled rows keep the server's number, which cannot drift
 * because it is not a function of now.
 *
 * Rounded, matching `TimeEntry.elapsedMinutes` in the Kotlin, so a row does not jump by a
 * minute the moment a refetch replaces the local reading with the server's.
 */
export function elapsedSince(startedAt: string, now: Date): number {
  const started = new Date(startedAt).getTime();
  if (Number.isNaN(started)) return 0;
  return Math.max(0, Math.round((now.getTime() - started) / 60_000));
}

/**
 * What the panel's heading says about the total.
 *
 * **"Nothing logged yet" and not `0h`**, which is the one distinction worth making here: a
 * ticket nobody has clocked and a ticket somebody logged nought minutes against are
 * different facts, and `0h` at the top of a panel reads as a number that failed to load.
 * `entries.length` is what decides, never `totalMinutes` — a ticket whose only row is a
 * *running* clock has a total of 0 and is emphatically not un-logged, and that is the case a
 * `totalMinutes === 0` test would get wrong.
 *
 * The running clock is named separately and never folded into the total, following the
 * server: a figure somebody invoices must not move when the page is refreshed. It travels
 * beside the total the way `unestimated` travels with every points total in this app.
 */
export function totalSentence(time: TicketTime, runningMinutes?: number): string {
  const settled = time.entries.filter((entry) => entry.minutes !== undefined);
  const clocks = time.entries.length - settled.length;

  const parts: string[] = [];
  if (settled.length === 0) {
    parts.push(time.entries.length === 0 ? "Nothing logged yet" : "Nothing settled yet");
  } else {
    parts.push(`${workedTime(time.totalMinutes)} logged over ${entryCount(settled.length)}`);
  }

  if (runningMinutes !== undefined) {
    parts.push(`your timer has been running ${workedTime(runningMinutes)}`);
  } else if (clocks > 0) {
    // Somebody else's clock, and named as theirs: two people clocking one ticket is
    // ordinary, and a reader seeing a total that is about to grow should know why.
    parts.push(`${clocks === 1 ? "another clock is" : `${clocks} other clocks are`} running`);
  }

  return `${parts.join(", and ")}.`;
}

const entryCount = (count: number) => `${count} ${count === 1 ? "entry" : "entries"}`;

/** One day of a timesheet: what was spent, and on what. */
export type TimeDay = { spentOn: string; minutes: number; entries: TimeEntry[] };

/**
 * The rows grouped into days, newest day first.
 *
 * Grouped on `spentOn` and never on `startedAt` or `createdAt`: half the rows have no
 * `startedAt` at all, and `createdAt` is when somebody *typed* the entry — which for anybody
 * logging Friday's work on Monday files it under the wrong day, and that is the normal case
 * rather than the edge one. `V39` gives `spent_on` a column of its own for exactly this read.
 *
 * A day's `minutes` sums only settled rows, so a day holding a running clock reports what has
 * actually been claimed. The clock is still in `entries` for the row to draw.
 *
 * The server already returns newest-first; this re-sorts anyway rather than trusting it,
 * because a grouping that silently depends on the order it was handed is a grouping that
 * breaks the day somebody adds a `?order=` parameter.
 */
export function byDay(entries: TimeEntry[]): TimeDay[] {
  const days = new Map<string, TimeEntry[]>();
  for (const entry of entries) {
    const bucket = days.get(entry.spentOn);
    if (bucket) bucket.push(entry);
    else days.set(entry.spentOn, [entry]);
  }
  return [...days.entries()]
    .map(([spentOn, rows]) => ({
      spentOn,
      minutes: rows.reduce((total, entry) => total + (entry.minutes ?? 0), 0),
      entries: rows,
    }))
    .sort((a, b) => (a.spentOn < b.spentOn ? 1 : a.spentOn > b.spentOn ? -1 : 0));
}

/**
 * `3h 30m`, `2:30`, `150` — all of them 150 minutes.
 *
 * A duration is the one field on this panel that people will not type in one shape. A form
 * that took only minutes would have somebody entering `2.5` and logging two and a half
 * minutes against a client; one that took only `h:mm` would refuse `45m`. So all three are
 * read, and a bare number is minutes because that is what the wire carries and what the
 * placeholder says.
 *
 * Null for anything it cannot read, and the caller must show that as a refusal rather than
 * sending a guess: silently logging 0 for `about an hour` is how a timesheet stops being
 * checkable. Deliberately no support for `2.5h` decimals — a decimal hour is the shape that
 * cannot add up (`V39` refuses `NUMERIC` for the same reason), and `2h 30m` is unambiguous.
 */
export function parseWorkedTime(raw: string): number | null {
  const text = raw.trim().toLowerCase();
  if (text === "") return null;

  const clock = /^(\d{1,4}):([0-5]\d)$/.exec(text);
  if (clock) return Number(clock[1]) * 60 + Number(clock[2]);

  const words = /^(?:(\d{1,4})\s*h)?\s*(?:(\d{1,4})\s*m(?:in)?)?$/.exec(text);
  if (words && (words[1] !== undefined || words[2] !== undefined)) {
    return Number(words[1] ?? 0) * 60 + Number(words[2] ?? 0);
  }

  const bare = /^(\d{1,5})$/.exec(text);
  if (bare) return Number(bare[1]);

  return null;
}
