"use client";

import { useMyStats, useTeams } from "@/lib/queries";
import { CATEGORY_ORDER, isOpen } from "@/lib/status-order";
import { CATEGORY_LABELS, categoryOfTicket } from "@/lib/statuses";
import { MyTicketRow, TicketGroup } from "./ticket-rows";
import { useMyOpenTickets } from "./use-my-work";

/**
 * Everything open with my name on it, in every team, stacked by **category**.
 *
 * By category and not by status since `KAN-90`, for the reason `KAN-28` gave for every
 * other cross-team list: this screen holds one person's work in every team they are in,
 * so it holds several vocabularies, and a header has to be the fact they agree on rather
 * than whichever team's word came back first. Two teams may both call a status `review`
 * and mean different things by it, so a bucket keyed on the bare word would stack two
 * meanings in one pile.
 *
 * Downwards as the work flows, through `CATEGORY_ORDER` — the same sequence the server
 * ranks a cross-team page by, so this screen does not introduce a sixth opinion about
 * where started work goes.
 *
 * A bucket nothing is in gets no header. `TicketQueryRepository.groupCounts` makes the
 * same ruling about the same question — "the set of possible statuses is not the set of
 * statuses this question found" — and a `Backlog · 0` above nothing is a heading over air.
 */

/** The open categories, in reading order. Membership derived, sequence written down. */
const GROUPS = CATEGORY_ORDER.filter(isOpen);

export function AssignedTab() {
  const open = useMyOpenTickets();
  const stats = useMyStats();

  const rows = open.data ?? [];
  // Each row against its *own* team's catalogue, which is the only thing that knows what
  // its word means — `labelOf` reads the same way, and for the same reason.
  const teams = useTeams();
  const groups = GROUPS.flatMap((category) => {
    const held = rows.filter(
      (ticket) => categoryOfTicket(teams.data ?? [], ticket.teamId, ticket.status) === category,
    );
    return held.length === 0 ? [] : [{ status: category, held }];
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
        <TicketGroup key={status} label={CATEGORY_LABELS[status]} count={held.length}>
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
