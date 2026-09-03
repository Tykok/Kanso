"use client";

import Link from "next/link";
import { ticketAddress, ticketHref } from "@/lib/api";
import { useTicketLinks } from "@/lib/queries";
import { linkGroups } from "./ticket-links";

/**
 * Every edge touching this ticket, in sections.
 *
 * Replaces the "Depends on" chips this page used to derive from the timeline response.
 * That read only one of the five sentences a ticket's graph can say, and it read it from
 * a *scoped* query — so a ticket outside the chart's current scope drew no chips at all
 * rather than the ones it has. There is a per-ticket endpoint now.
 *
 * Drawn only when it has something to draw, exactly as the panel does with a field it
 * cannot fill: a ticket with no links is the overwhelming majority of them, and a
 * permanent empty "Links" heading would cost every one of those pages a line.
 */
export function TicketLinksPanel({ ticketId }: { ticketId: string }) {
  const links = useTicketLinks(ticketId);
  const groups = linkGroups(links.data ?? []);
  if (groups.length === 0) return null;

  return (
    <div className="flex flex-col gap-1.5">
      {groups.map((group) => (
        <div key={group.label} className="flex flex-wrap items-center gap-2.5 text-12 text-faint">
          <span className="shrink-0">{group.label}</span>
          {group.tickets.map((other) => (
            <Link
              key={other.id}
              href={ticketHref(ticketAddress(other))}
              className="inline-flex h-6 items-center rounded-sm bg-accent-soft px-2.5 font-mono text-11 text-accent-ink"
            >
              {other.identifier ?? other.title}
            </Link>
          ))}
        </div>
      ))}
    </div>
  );
}
