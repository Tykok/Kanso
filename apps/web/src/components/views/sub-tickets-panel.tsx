"use client";

import Link from "next/link";
import { ticketAddress, ticketHref, type SubTicketProgress } from "@/lib/api";
import { useSubTickets } from "@/lib/queries";

/**
 * What a parent's progress is allowed to say.
 *
 * A count, and points only when the whole parent is sized. Deliberately not a percentage:
 * KAN-40's rule is that nothing should be shown that reads like a grade, and "60 %"
 * against a job broken into five pieces is a mark out of ten with the working hidden. "3
 * of 5 done" is the same information and a reader can check it.
 *
 * Exported so the sentence is provable without rendering anything.
 */
export function progressLabel(progress: SubTicketProgress): string {
  const counted = `${progress.done} of ${progress.total} done`;
  // `== null`, not `=== null`, and the difference was a visible bug: the server omits a
  // null field rather than serialising it, so an unestimated parent arrives with these
  // two *absent* and a strict check let it through to print "undefined of undefined pts".
  if (progress.donePoints == null || progress.totalPoints == null) return counted;
  return `${counted} · ${progress.donePoints} of ${progress.totalPoints} pts`;
}

/**
 * The sub-tickets of one ticket, and how much of it is finished.
 *
 * The fraction lives here rather than on the list's rows, and that is the KAN-59-shaped
 * decision in this feature. The main list holds a 200-row page of an uncapped bucket, so
 * a parent's children are frequently not in it — a fraction computed from the rows on
 * screen would read "1 of 2 done" for a parent with nine children, which is the same
 * class of error as a bucket count that disagreed with its rows. The list draws the
 * structure it can prove (a chevron, and the children it actually holds); the number is
 * drawn where it can be asked for properly.
 *
 * Absent entirely for a ticket with no children, which is most of them.
 */
export function SubTicketsPanel({ ticketId }: { ticketId: string }) {
  const { children, progress } = useSubTickets(ticketId);
  const rows = children.data ?? [];
  if (rows.length === 0) return null;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-baseline gap-2.5 text-12 text-faint">
        <span>Sub-tickets</span>
        {progress.data && (
          <span data-testid="sub-ticket-progress" className="font-mono text-11">
            {progressLabel(progress.data)}
          </span>
        )}
      </div>
      {rows.map((child) => (
        <Link
          key={child.id}
          href={ticketHref(ticketAddress(child))}
          className="flex items-center gap-2.5 pl-4 text-12 hover:text-accent-ink"
        >
          <span className="w-16 shrink-0 font-mono text-11 text-faint">
            {child.identifier ?? "—"}
          </span>
          <span className="truncate">{child.title}</span>
        </Link>
      ))}
    </div>
  );
}
