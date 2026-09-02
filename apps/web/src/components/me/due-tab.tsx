"use client";

import { useMemo } from "react";
import { useMyStats } from "@/lib/queries";
import { MyTicketRow, TicketGroup } from "./ticket-rows";
import { useMyOpenTickets } from "./use-my-work";
import { DUE_BUCKETS, dueBucketOf, type DueBucket, type DueClock } from "./work-buckets";

/**
 * Mine with a deadline: overdue, this week, later.
 *
 * Three groups and not four. A ticket with no date is not in a fourth pile called "no
 * date" — that would be the Assigned tab again under a heading that promised deadlines —
 * so it is counted in a sentence at the foot instead, where "how much of my plate has no
 * date at all" is a fact about the tab rather than a group in it.
 *
 * The two decisions this tab makes — when a deadline has passed, and where this week ends
 * — are both in `work-buckets.ts`, where they can be asserted against the same rules
 * `MyStatsService` counts `strip.overdue` by. The strip and the first group have to hold
 * the same tickets, and the way to make that true is to write the rule once.
 */

const LABELS: Record<DueBucket, string> = {
  overdue: "Overdue",
  thisWeek: "This week",
  later: "Later",
};

export function DueTab() {
  const open = useMyOpenTickets();
  const stats = useMyStats();

  /**
   * The moment the numbers were read, not the moment this renders.
   *
   * `Date.now()` at render would drift away from `strip.overdue` — which the server
   * decided against its own clock when it answered — so a ticket could be red in the
   * strip and in "This week" underneath it, for a minute, with nothing on screen
   * admitting the two were measured at different times. Reading the clock off
   * `dataUpdatedAt` is the same trick `app/(app)/inbox/page.tsx` uses to time its rows:
   * one instant for the whole screen, moving only when the data does.
   *
   * The civil day is assembled from the local clock rather than sliced off
   * `toISOString()`, for the reason `timeline-geometry.ts`'s `today()` gives: that is
   * UTC's today, and west of Greenwich it names tomorrow for most of the evening.
   */
  const clock = useMemo<DueClock | undefined>(() => {
    // The last of the twelve buckets is this week, and its Monday is the server's own
    // answer to where a week begins. Nothing here computes a week boundary.
    const weekStartsOn = stats.data?.weeks.at(-1)?.startsOn;
    if (weekStartsOn === undefined || stats.dataUpdatedAt === 0) return undefined;

    const at = new Date(stats.dataUpdatedAt);
    const pad = (value: number) => String(value).padStart(2, "0");
    return {
      day: `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}`,
      at: stats.dataUpdatedAt,
      weekStartsOn,
    };
  }, [stats.data, stats.dataUpdatedAt]);

  const rows = open.data ?? [];
  const dated = clock
    ? rows.flatMap((ticket) => {
        const bucket = dueBucketOf(ticket.due, clock);
        return bucket ? [{ ticket, bucket }] : [];
      })
    : [];
  const undated = rows.length - dated.length;

  const pending = open.isPending || clock === undefined;

  return (
    <div className="flex flex-col gap-3 px-6 pt-4 pb-6">
      {pending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

      {!pending && dated.length === 0 && (
        <p className="empty">
          {rows.length === 0
            ? "Nothing open is assigned to you."
            : "None of your open tickets has a due date."}
        </p>
      )}

      {/* Overdue first: the group that is a problem goes where the reader looks first, and
          the sequence is the calendar's own rather than a stack of counts. */}
      {DUE_BUCKETS.flatMap((bucket) => {
        const held = dated.filter((row) => row.bucket === bucket);
        return held.length === 0
          ? []
          : [
              <TicketGroup key={bucket} label={LABELS[bucket]} count={held.length}>
                {held.map(({ ticket }) => (
                  <MyTicketRow key={ticket.id} ticket={ticket} />
                ))}
              </TicketGroup>,
            ];
      })}

      {/* Never `0 with no date`, which would be a sentence about the absence of a problem. */}
      {!pending && undated > 0 && (
        <p className="m-0 px-row-x pt-2 text-11 text-faint">
          {undated} of your {rows.length} open tickets {undated === 1 ? "has" : "have"} no due
          date, so {undated === 1 ? "it is" : "they are"} in none of these groups.
        </p>
      )}
    </div>
  );
}
