"use client";

import { useTicketDuration } from "@/lib/queries";
import { durationCopy } from "@/lib/ticket-duration";

/**
 * How long this ticket should take, on whichever surface is drawing it.
 *
 * One component for the panel and the page, because the interesting part is the copy and
 * two copies of it would drift. `durationCopy` decides what is said; this only places it.
 *
 * It renders in every state and is never conditional on having an answer. The three ways
 * of not knowing are three different sentences, each naming the gesture that fixes it, and
 * a field that vanished instead would leave the reader unable to tell a ticket Kanso
 * cannot date from a request that failed.
 */
export function TicketDurationNote({ ticketId }: { ticketId: string }) {
  const duration = useTicketDuration(ticketId);

  if (duration.isPending) return <span className="text-11 text-faint">Estimating…</span>;
  if (duration.isError) {
    return <span className="text-11 text-urgent">Could not estimate how long this will take.</span>;
  }

  const copy = durationCopy(duration.data);

  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      {/* An em dash and then the reason, rather than an empty line: the sentence below is
          the whole answer in three of the four cases. */}
      <span className="text-13 text-foreground">{copy.value ?? "—"}</span>
      <span className="text-11 text-faint">{copy.note}</span>
    </div>
  );
}
