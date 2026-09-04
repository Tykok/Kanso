"use client";

import { useEffect, useState } from "react";
import { todayValue, type TimeEntry } from "@/lib/api";
import {
  useCorrectTime,
  useLogTime,
  useRemoveTime,
  useStartTimer,
  useStopTimer,
  useTicketTime,
  useUsers,
} from "@/lib/queries";
import { actionErrorMessage } from "@/lib/errors";
import { byDay, elapsedSince, parseWorkedTime, totalSentence, workedTime } from "@/lib/time-entries";

/**
 * KAN-26 — one ticket's hours, and the clock that produces them.
 *
 * Mounted on the ticket page beside `SubTicketsPanel` and `TicketLinksPanel`, and **not** in
 * `detail-panel.tsx`, which another branch owns this week. That is a scheduling fact rather
 * than a design one: the panel and the page are two presentations of one ticket and which a
 * person sees is their `openTicket` preference, so this belongs in both. `TicketPullRequests`
 * says as much about itself, having been missed in one of the two and invisible to every test.
 *
 * **Drawn even when it has nothing to draw**, which is the opposite of what its two
 * neighbours decided and is deliberate. A ticket with no links is the overwhelming majority
 * of them, so a permanent "Links" heading would cost every page a line for nothing. Hours are
 * the reverse: on an instance that bills by the hour a ticket with nothing logged is the one
 * that needs the affordance most, because the whole feature is worthless if starting a clock
 * requires first having logged something. So the heading is always there and it says *Nothing
 * logged yet*, which is a sentence rather than a `0h`.
 */
export function TimePanel({ ticketId }: { ticketId: string }) {
  const time = useTicketTime(ticketId);
  const users = useUsers();
  const start = useStartTimer(ticketId);
  const stop = useStopTimer();

  /**
   * The clock has to move. A running row rendered once from the server's `elapsedMinutes`
   * would sit at `3m` for an hour, which reads as a timer that failed rather than one that is
   * going. Fifteen seconds is under the minute the display is rounded to, so the number is
   * never more than a moment stale, and this is one `Date` in state rather than a refetch.
   */
  const [now, setNow] = useState(() => new Date());
  const running = time.data?.entries.find((entry) => entry.id === time.data?.runningId);
  useEffect(() => {
    if (!running) return;
    const tick = setInterval(() => setNow(new Date()), 15_000);
    return () => clearInterval(tick);
  }, [running]);

  const runningMinutes = running?.startedAt ? elapsedSince(running.startedAt, now) : undefined;
  const failure = start.error ?? stop.error;

  return (
    <div className="flex flex-col gap-2.5" data-testid="time-panel">
      <div className="flex flex-wrap items-baseline gap-2.5 text-12 text-faint">
        <span>Time</span>
        {time.data && (
          <span data-testid="time-total">{totalSentence(time.data, runningMinutes)}</span>
        )}
      </div>

      {/* A clock is one button that changes its mind, not two: at any instant exactly one of
          start and stop is a thing this person can do on this ticket, and offering both would
          invite the 409 the server exists to refuse. */}
      <div className="flex flex-wrap items-center gap-2">
        {running ? (
          <button
            type="button"
            data-testid="stop-timer"
            className="inline-flex h-[26px] items-center gap-2 rounded-md bg-urgent/10 px-2.5 text-12 text-urgent"
            disabled={stop.isPending}
            onClick={() => stop.mutate(running.id)}
          >
            ■ Stop timer
            <span className="font-mono">{workedTime(runningMinutes ?? 0)}</span>
          </button>
        ) : (
          <button
            type="button"
            data-testid="start-timer"
            className="inline-flex h-[26px] items-center gap-2 rounded-md bg-accent px-2.5 text-12 text-muted-foreground hover:text-foreground"
            disabled={start.isPending}
            onClick={() => start.mutate({ spentOn: todayValue() })}
          >
            ▶ Start timer
          </button>
        )}
        <LogForm ticketId={ticketId} />
      </div>

      {/* The 409 naming the ticket somebody left a clock on is the most useful sentence this
          feature produces, so it is shown rather than swallowed. `actionErrorMessage` is what
          every other write on this page reads a problem document with. */}
      {failure && (
        <p className="m-0 max-w-[620px] text-12 text-urgent" data-testid="time-error">
          {actionErrorMessage(failure)}
        </p>
      )}

      {time.data &&
        byDay(time.data.entries).map((day) => (
          <div key={day.spentOn} className="flex flex-col gap-1">
            <div className="flex items-baseline gap-2.5 pl-4 text-11 text-faint">
              {/* The stored day, printed as the stored day. `new Date(day)` would parse it as
                  UTC midnight and render the day before for every reader west of Greenwich —
                  the trap `lib/api/core.ts` warns about for a `hasTime: false` instant. */}
              <span>{day.spentOn}</span>
              <span className="font-mono">{workedTime(day.minutes)}</span>
            </div>
            {day.entries.map((entry) => (
              <EntryRow
                key={entry.id}
                entry={entry}
                who={users.data?.find((person) => person.id === entry.userId)?.displayName}
                runningMinutes={entry.id === time.data?.runningId ? runningMinutes : undefined}
              />
            ))}
          </div>
        ))}
    </div>
  );
}

/**
 * One row: what it cost, what it was, whose it was.
 *
 * The duration is an input rather than text, and that is the gesture the whole schema is
 * shaped around — `V39` stores the duration precisely so "that was forty minutes, not nine
 * hours" is one correction and does not have to lie about when the clock stopped. Saved on
 * blur, as the description on this page is, because a keystroke is not an edit worth a
 * request.
 *
 * A running row's duration is **not** editable: there is nothing to correct yet, and the
 * server refuses it. It shows the clock instead.
 */
function EntryRow({
  entry,
  who,
  runningMinutes,
}: {
  entry: TimeEntry;
  who?: string;
  runningMinutes?: number;
}) {
  const correct = useCorrectTime();
  const remove = useRemoveTime();
  // `=== undefined` and not `!entry.minutes`: a settled entry of 0 minutes is a real row, and
  // a falsy test would draw it as though its clock were still running.
  const isRunning = entry.minutes === undefined;
  const [refused, setRefused] = useState(false);

  return (
    <div className="flex items-center gap-2.5 pl-4 text-12" data-testid="time-entry">
      {isRunning ? (
        <span className="w-20 shrink-0 font-mono text-11 text-urgent" data-testid="running-clock">
          ▶ {workedTime(runningMinutes ?? entry.elapsedMinutes)}
        </span>
      ) : (
        <input
          aria-label="Duration"
          data-testid="entry-duration"
          defaultValue={workedTime(entry.minutes ?? 0)}
          className={`w-20 shrink-0 border-none bg-transparent p-0 font-mono text-11 ${refused ? "text-urgent" : "text-foreground"}`}
          onKeyDown={(event) => event.stopPropagation()}
          onBlur={(event) => {
            const minutes = parseWorkedTime(event.target.value);
            // Refused rather than guessed. `2.5` read as two minutes would put a wrong
            // duration on an invoice with nothing on screen to say so.
            if (minutes === null || minutes === entry.minutes) {
              setRefused(minutes === null);
              return;
            }
            setRefused(false);
            correct.mutate({ entryId: entry.id, body: { minutes } });
          }}
        />
      )}
      {/* Absent, never the string "undefined": the server omits a null note. */}
      <span className="min-w-0 flex-1 truncate text-muted-foreground">{entry.note ?? "—"}</span>
      {/* A clocked entry is marked as measured. A typed one is somebody's recollection, and a
          reader auditing an invoice is entitled to know which is which. */}
      {!isRunning && entry.startedAt && (
        <span className="shrink-0 text-11 text-faint" title="Measured by a timer">
          clocked
        </span>
      )}
      <span className="w-24 shrink-0 truncate text-11 text-faint">{who ?? "—"}</span>
      <button
        type="button"
        aria-label="Delete this entry"
        data-testid="delete-entry"
        className="shrink-0 text-11 text-faint hover:text-urgent"
        disabled={remove.isPending}
        onClick={() => remove.mutate(entry.id)}
      >
        ×
      </button>
    </div>
  );
}

/**
 * A duration somebody types, for work already done.
 *
 * `spentOn` defaults to the browser's today and is editable, because the common case is
 * logging Friday's work on Monday — `V39` refuses to derive the day from `created_at` for
 * exactly that reason, and the server's own fallback is UTC.
 *
 * The duration field accepts `3h 30m`, `2:30` and a bare minute count, and shows a refusal
 * rather than sending a guess. See `parseWorkedTime`.
 */
function LogForm({ ticketId }: { ticketId: string }) {
  const log = useLogTime(ticketId);
  const [duration, setDuration] = useState("");
  const [note, setNote] = useState("");
  const [spentOn, setSpentOn] = useState(() => todayValue());
  const minutes = parseWorkedTime(duration);

  return (
    <form
      className="flex flex-wrap items-center gap-1.5"
      onSubmit={(event) => {
        event.preventDefault();
        if (minutes === null) return;
        log.mutate(
          { minutes, note: note || undefined, spentOn },
          { onSuccess: () => (setDuration(""), setNote("")) },
        );
      }}
    >
      <input
        aria-label="Duration to log"
        data-testid="log-duration"
        placeholder="45m"
        value={duration}
        onChange={(event) => setDuration(event.target.value)}
        onKeyDown={(event) => event.stopPropagation()}
        className="h-[26px] w-16 rounded-md bg-accent px-2 font-mono text-11 text-foreground"
      />
      <input
        aria-label="What the time was spent on"
        data-testid="log-note"
        placeholder="What for?"
        value={note}
        onChange={(event) => setNote(event.target.value)}
        onKeyDown={(event) => event.stopPropagation()}
        className="h-[26px] w-40 rounded-md bg-accent px-2 text-11 text-foreground"
      />
      <input
        type="date"
        aria-label="Day worked"
        data-testid="log-day"
        value={spentOn}
        onChange={(event) => setSpentOn(event.target.value)}
        className="h-[26px] rounded-md bg-accent px-2 text-11 text-muted-foreground"
      />
      {/* Disabled on anything the parser refuses, so a typo cannot become a request. An empty
          field is refused the same way, which is why this is not a `required` attribute: the
          two refusals are one rule and it lives in `parseWorkedTime`. */}
      <button
        type="submit"
        data-testid="log-submit"
        disabled={minutes === null || log.isPending}
        className="h-[26px] rounded-md bg-accent px-2.5 text-11 text-muted-foreground hover:text-foreground disabled:opacity-40"
      >
        Log
      </button>
    </form>
  );
}
