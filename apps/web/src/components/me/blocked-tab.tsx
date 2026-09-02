"use client";

import { useMemo } from "react";
import { useMyStats } from "@/lib/queries";
import { BlockedTicketRow, TicketGroup } from "./ticket-rows";
import { useDependencyGraph, useMyOpenTickets } from "./use-my-work";
import { blockages, type Upstream } from "./work-buckets";

/**
 * Mine whose predecessor is unfinished, and which predecessor it is.
 *
 * **Where the arrows come from, and what that costs.** There is no per-ticket dependency
 * endpoint and no `blocked` facet in `TicketFilterVocabulary.SERVED`, so the edges are
 * read off `/api/timeline` — which is where `components/views/ticket-page.tsx` already
 * reads "what this ticket waits on", for the same reason and with the same shrug.
 * Unscoped, because a personal home has no team selected and a scoped read would answer
 * "nothing is blocking you" about arrows it never looked at.
 *
 * That response draws *bars*, though, and a bar needs a date: `TimelineService` puts only
 * the dated tickets in `tickets`, while the edges it sends cover the whole dependency
 * closure. So a ticket held up by an undated predecessor arrives here as an arrow whose
 * far end has no status to read. Guessing at it is out of the question in both directions
 * — calling it blocked would invent a block, calling it clear would hide one — so it is
 * *counted* instead, and the count is printed against `strip.blocked`, which the server
 * worked out from the rows themselves and is therefore the number to trust.
 *
 * The honest fix is a server one and is not this branch's: either a `blocked` facet on
 * `/api/tickets` or a `blockedBy` on the wire for the caller's own rows. Until then this
 * tab lists what it can name and says how much it could not, which is the same shape
 * `TimelineView.truncated` already uses to admit a chart is missing bars.
 */

export function BlockedTab() {
  const open = useMyOpenTickets();
  const graph = useDependencyGraph();
  const stats = useMyStats();

  // Memoised rather than written inline: `?? []` is a fresh array on every render, and
  // both memos below take it as a dependency — so the two walks over the graph would run
  // again on every keystroke anywhere on the page.
  const rows = useMemo(() => open.data ?? [], [open.data]);

  /**
   * Every predecessor this screen can put a name and a status to.
   *
   * Two sources, and the second is not redundant: my own open tickets block each other
   * often, and they are in hand whether or not they carry a date — so folding them in
   * resolves the commonest undated predecessor there is without a second request.
   */
  const upstream = useMemo(() => {
    const named = new Map<string, Upstream>();
    for (const bar of graph.data?.tickets ?? []) {
      named.set(bar.id, {
        id: bar.id,
        identifier: bar.identifier,
        title: bar.title,
        status: bar.status,
      });
    }
    for (const ticket of rows) {
      named.set(ticket.id, {
        id: ticket.id,
        identifier: ticket.identifier,
        title: ticket.title,
        status: ticket.status,
      });
    }
    return named;
  }, [graph.data, rows]);

  const { blocked, unresolved } = useMemo(
    () => blockages(rows, graph.data?.dependencies ?? [], upstream),
    [rows, graph.data, upstream],
  );

  const pending = open.isPending || graph.isPending;
  const counted = stats.data?.strip.blocked;
  // The server's count against what could be named. `unresolved` is this side's own
  // reading of the same gap; the larger of the two is the honest thing to print, since
  // either read can be the narrower one.
  const missing = Math.max(counted === undefined ? 0 : counted - blocked.length, unresolved);

  return (
    <div className="flex flex-col gap-3 px-6 pt-4 pb-6">
      {pending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

      {!pending && blocked.length === 0 && (
        <p className="empty">
          {rows.length === 0
            ? "Nothing open is assigned to you."
            : "Nothing you are holding is waiting on unfinished work."}
        </p>
      )}

      {blocked.length > 0 && (
        <TicketGroup label="Waiting on" count={blocked.length}>
          {blocked.map(({ ticket, waitingOn }) => (
            <BlockedTicketRow key={ticket.id} ticket={ticket} waitingOn={waitingOn} />
          ))}
        </TicketGroup>
      )}

      {!pending && missing > 0 && (
        <p className="m-0 px-row-x pt-2 text-11 text-faint" data-testid="me-blocked-gap">
          {missing} more of your tickets {missing === 1 ? "is" : "are"} blocked by work this
          screen cannot name: a ticket with no dates has no bar on the timeline, which is
          where these arrows are read from.
        </p>
      )}

      {/* Said separately, because it is a different absence: the closure was capped, so
          arrows are missing rather than unreadable. */}
      {graph.data?.truncated === true && (
        <p className="m-0 px-row-x text-11 text-faint">
          The dependency graph was too large to read whole, so some arrows are missing.
        </p>
      )}
    </div>
  );
}
