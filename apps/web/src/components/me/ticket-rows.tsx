"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { StatusPill, TicketIdentifier } from "@/components/pills";
import { GroupLabel } from "@/components/ui/group-label";
import { PriorityMark } from "@/components/ui/priority-mark";
import { Row } from "@/components/ui/row";
import { dayValue, ticketAddress, ticketHref, type Ticket } from "@/lib/api";
import type { Upstream } from "./work-buckets";

/**
 * One line of somebody's own work, for the three tabs that list tickets.
 *
 * Deliberately **not** `components/tickets.tsx`'s `TicketList`. That component is the
 * right one for the ticket list and the wrong one here, and the reason is its
 * `ActionContext`: it takes rows, a cursor, a selection, a rename callback and a project
 * catalogue, because it is the surface `j`, `e`, `x` and `⋯` act on. `/me` has no cursor
 * and no selection — every row is a link out to the ticket — and its rows come from every
 * team at once, so the team-scoped context those actions read would be wrong for most of
 * them. Handing it an inert context to satisfy the type would be a menu that does nothing
 * on every row, which is the class of lying control this whole pass is removing.
 *
 * What *is* reused is everything below the list: `Row` for the height, the padding and
 * the hover, `TicketIdentifier`, `PriorityMark` and `StatusPill` for the marks. Nothing
 * here draws a new colour, a new glyph or a new row height — the visual language is the
 * app's, only the columns are this screen's.
 */

/** id, priority, status, title, due — narrower than the list's nine columns. */
const COLS = "grid-cols-[70px_20px_108px_1fr_60px]";

/** The same five, with what a ticket waits on where its deadline would be. */
const BLOCKED_COLS = "grid-cols-[70px_20px_108px_1fr_minmax(120px,220px)]";

/**
 * A group of rows under its own caption, with a count.
 *
 * The count is of the rows drawn and says so by sitting on them: this screen fetches a
 * page of its own work rather than asking the server to stack it, so a header claiming to
 * be a fact about the team — the thing `TicketQueryRepository.groupCounts` exists to
 * answer — would be a fact about a fetch. See `assigned-tab.tsx` for what is printed when
 * the page is shorter than the strip.
 */
export function TicketGroup({
  label,
  count,
  children,
}: {
  label: string;
  count: number;
  children: ReactNode;
}) {
  return (
    <section className="flex flex-col gap-row">
      <GroupLabel className="flex items-baseline gap-2 px-row-x">
        <span>{label}</span>
        <span className="font-mono tracking-normal normal-case">{count}</span>
      </GroupLabel>
      {children}
    </section>
  );
}

export function MyTicketRow({ ticket }: { ticket: Ticket }) {
  return (
    <TicketLink ticket={ticket}>
      <Row data-testid="me-ticket-row" className={`grid ${COLS}`}>
        <Marks ticket={ticket} />
        {/* `MM-DD`, sliced off the ISO string, as every other row in the app prints a day:
            a day is never run through a `Date`. Blank and not `—` for a ticket with no
            deadline — the Due tab never draws one, and on Assigned an em dash in every
            row would be a column of nothing. */}
        <span className="text-11 text-faint">
          {ticket.due ? dayValue(ticket.due).slice(5) : ""}
        </span>
      </Row>
    </TicketLink>
  );
}

/**
 * A blocked row, which is genuinely a different row: it has to name the thing holding it.
 *
 * "Blocked" is not a property a reader can act on — the ticket they have to go and look
 * at is the *predecessor* — so the row carries that predecessor's identifier where every
 * other row carries a date. A row that only said "blocked" would send the reader to the
 * ticket page to find out by what, which is the trip this tab exists to save.
 */
export function BlockedTicketRow({
  ticket,
  waitingOn,
}: {
  ticket: Ticket;
  waitingOn: readonly Upstream[];
}) {
  return (
    <TicketLink ticket={ticket}>
      <Row data-testid="me-blocked-row" className={`grid ${BLOCKED_COLS}`}>
        <Marks ticket={ticket} />
        <span
          className="truncate font-mono text-11 text-faint"
          title={waitingOn.map((row) => `${row.identifier ?? row.id} — ${row.title}`).join("\n")}
        >
          {/* The identifiers and not the titles: a title needs the width the row does not
              have, and the identifier is what the reader types or clicks next. The full
              names are in the `title`, which is where a row's overflow already goes. */}
          {waitingOn.map((row) => row.identifier ?? "?").join(" ")}
        </span>
      </Row>
    </TicketLink>
  );
}

/** The four columns both rows open with. */
function Marks({ ticket }: { ticket: Ticket }) {
  return (
    <>
      <TicketIdentifier ticket={ticket} className="font-mono text-11 text-faint" />
      <PriorityMark priority={ticket.priority} />
      <StatusPill status={ticket.status} />
      <span className="truncate">{ticket.title}</span>
    </>
  );
}

/**
 * `className="contents"` so the link adds no box: the `<Row>` inside keeps the grid it
 * declares, exactly as the project page's ticket rows do. A whole-row link rather than a
 * linked title, because there is nothing else on this row to click.
 */
function TicketLink({ ticket, children }: { ticket: Ticket; children: ReactNode }) {
  return (
    <Link href={ticketHref(ticketAddress(ticket))} className="contents">
      {children}
    </Link>
  );
}
