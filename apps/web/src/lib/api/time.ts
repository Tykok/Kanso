import { request } from "./core";

/**
 * KAN-26 — the hours somebody says they worked, on one ticket.
 *
 * The wire shapes are `dev.kanso.api.TimeEntryController`'s response DTOs transcribed, and
 * the only translations are Jackson's two: `java.time` values are ISO-8601 strings, and a
 * null property is **omitted**.
 *
 * That omission carries the whole grammar of this feature, so it is worth stating once here
 * rather than three times below. A row has two absences and they mean different things:
 *
 *   * **`minutes` absent → the clock is still running.** This is the load-bearing one. It is
 *     not "zero minutes" and it is not a field that failed to load; it is the server saying
 *     there is no duration yet. A `minutes === null` test would miss every running entry,
 *     and a `minutes ?? 0` would silently report a clock that has run since Tuesday as
 *     nothing at all.
 *   * **`startedAt` absent → the duration was typed, not clocked.** Provenance, not
 *     arithmetic. A settled clocked entry has both fields; a settled typed entry has only
 *     `minutes`; a running entry has only `startedAt`.
 *
 * `elapsedMinutes` is always present and is what a caller should read for a number: the
 * settled duration when there is one, the clock's reading when there is not. Read it and no
 * branch is needed; branch on `minutes === undefined` only to decide whether the row is
 * still running.
 */
export type TimeEntry = {
  id: string;
  ticketId: string;
  userId: string;
  /** A day, `YYYY-MM-DD`. Never timezone-converted — the same rule as a `hasTime: false` instant. */
  spentOn: string;
  /** Absent while the clock runs. See the module comment: absent is not zero. */
  minutes?: number;
  /** Absent for a duration somebody typed. */
  startedAt?: string;
  /** Always present. `minutes` when settled, the clock's reading when not. */
  elapsedMinutes: number;
  note?: string;
  createdAt: string;
  updatedAt: string;
};

/**
 * A ticket's hours.
 *
 * `totalMinutes` **excludes any running clock**, which is the server's decision and not
 * something to correct on the client: a billable total that moved on every refresh is the one
 * property an invoice figure must not have. `runningId` is how a screen knows the total is
 * not the whole story, and which of the rows in `entries` belongs to the person looking.
 *
 * `totalMinutes` is a real 0 when nothing is logged, not an absent field — a total of no rows
 * genuinely is nought minutes. It is `entries.length` that says "nothing logged", and the two
 * are read together.
 */
export type TicketTime = {
  totalMinutes: number;
  entries: TimeEntry[];
  /** The caller's own running entry on this ticket. Absent when they are not clocking it. */
  runningId?: string;
};

export type LogTimeBody = { minutes: number; note?: string; spentOn?: string };
export type StartTimerBody = { note?: string; spentOn?: string };
/** Every field optional; absent means unchanged. There is no `startedAt` — see the Kotlin. */
export type CorrectTimeBody = { minutes?: number; note?: string; spentOn?: string };

export const timeApi = {
  ofTicket: (ticketId: string) => request<TicketTime>(`/api/tickets/${ticketId}/time`),
  log: (ticketId: string, body: LogTimeBody) =>
    request<TimeEntry>(`/api/tickets/${ticketId}/time`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  start: (ticketId: string, body: StartTimerBody = {}) =>
    request<TimeEntry>(`/api/tickets/${ticketId}/time/start`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  stop: (entryId: string) => request<TimeEntry>(`/api/time/${entryId}/stop`, { method: "POST" }),
  correct: (entryId: string, body: CorrectTimeBody) =>
    request<TimeEntry>(`/api/time/${entryId}`, { method: "PATCH", body: JSON.stringify(body) }),
  remove: (entryId: string) => request<void>(`/api/time/${entryId}`, { method: "DELETE" }),
};

/**
 * Today, as the browser reckons it, in the `YYYY-MM-DD` the wire wants.
 *
 * The client sends `spentOn` on every write rather than letting the server default it, and
 * that is deliberate: the server's fallback is its own UTC date, which files a Paris evening
 * against yesterday. `V39` refuses to read `user_preferences.timezone` for a billable record,
 * so the browser is the only party that knows the person's actual day.
 *
 * `sv-SE` for the same reason `lib/timeline-geometry.ts` uses it: it is the locale whose
 * short date *is* `YYYY-MM-DD`, so no manual padding can get it wrong.
 */
export const todayValue = (now: Date = new Date()): string => now.toLocaleDateString("sv-SE");
