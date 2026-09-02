"use client";

import { useMyStats } from "@/lib/queries";
import { STATUS_LABELS } from "@/lib/status";
import { inOrder, isOpen, statusesWhere, WORKFLOW_ORDER } from "@/lib/status-order";
import { MyTicketRow, TicketGroup } from "./ticket-rows";
import { useMyOpenTickets } from "./use-my-work";

/**
 * Everything open with my name on it, in every team, stacked by status.
 *
 * Downwards as the work flows, through `WORKFLOW_ORDER` — the sequence a grouped list
 * stacks its buckets in everywhere else in the app, so this screen does not introduce a
 * sixth opinion about where `in_review` goes. Which statuses appear is asked of the
 * *category* and not written out, which is the whole payoff of `lib/status-order.ts`: the
 * day a seventh open status arrives, it draws itself here instead of quietly going
 * missing.
 *
 * A bucket nothing is in gets no header. `TicketQueryRepository.groupCounts` makes the
 * same ruling about the same question — "the set of possible statuses is not the set of
 * statuses this question found" — and a `Backlog · 0` above nothing is a heading over air.
 */

/** The open statuses, in reading order. Membership derived, sequence written down. */
const GROUPS = inOrder(statusesWhere(isOpen), WORKFLOW_ORDER);

export function AssignedTab() {
  const open = useMyOpenTickets();
  const stats = useMyStats();

  const rows = open.data ?? [];
  const groups = GROUPS.flatMap((status) => {
    const held = rows.filter((ticket) => ticket.status === status);
    return held.length === 0 ? [] : [{ status, held }];
  });

  /**
   * How many the strip counted, against how many arrived.
   *
   * `strip.open` is counted server-side over `WorkloadService.SCAN_LIMIT`; this list is
   * one page of `/api/tickets`. So somebody past the page can see a bigger number above a
   * shorter list, and the honest thing is to say which is which — a strip that disagreed
   * with the rows under it, silently, is exactly the sort of lying control this pass
   * exists to remove.
   */
  const counted = stats.data?.strip.open;
  const short = counted !== undefined && !open.isPending && rows.length < counted;

  return (
    <div className="flex flex-col gap-3 px-6 pt-4 pb-6">
      {open.isPending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

      {!open.isPending && rows.length === 0 && (
        <p className="empty">Nothing open is assigned to you.</p>
      )}

      {groups.map(({ status, held }) => (
        <TicketGroup key={status} label={STATUS_LABELS[status]} count={held.length}>
          {held.map((ticket) => (
            <MyTicketRow key={ticket.id} ticket={ticket} />
          ))}
        </TicketGroup>
      ))}

      {short && (
        <p className="m-0 px-row-x pt-2 text-11 text-faint">
          Showing {rows.length} of your {counted} open tickets.
        </p>
      )}
    </div>
  );
}
