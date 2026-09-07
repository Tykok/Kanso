"use client";

import { pendingWrites } from "@/lib/offline-write";
import { useOffline } from "@/store/offline";
import { cn } from "@/lib/utils";

/**
 * A row's own admission that it is behind — `KAN-89`.
 *
 * The list is the grouped answer, and a guess about a stacked entry is answered by
 * invalidating it: a bucket's count is the whole match and only SQL knows it. Offline
 * that refetch never lands, so the row goes on showing the status the server last gave
 * it. This says so, and leaves both the status and the count alone.
 *
 * The word is the banner's word for the same write, deliberately: `queued` and `refused`
 * are what the reader will find when they go looking for it there.
 *
 * The queue is read per row rather than computed once and threaded through the
 * virtualised list. `pendingWrites` walks a list that is empty on every screen where
 * nobody has written offline, which is nearly all of them, and the selector answers a
 * string — so a row re-renders when its own write appears and not when the array
 * identity changes.
 */
export function PendingWriteMark({ ticketId }: { ticketId: string }) {
  const state = useOffline((store) => pendingWrites(store.writes).get(ticketId));
  if (!state) return null;

  return (
    <span
      data-testid="pending-write"
      data-state={state}
      className={cn(
        "text-11 tracking-[0.05em] whitespace-nowrap uppercase",
        // The same two hues `SyncBadge` uses, for the same two meanings: amber is work
        // in flight, red is something somebody has to decide.
        state === "queued" ? "text-status-progress" : "text-urgent",
      )}
      title={
        state === "queued"
          ? "Queued — not saved yet. It goes when the network returns."
          : "The server refused this write. It is not saved yet — see the queue on this screen."
      }
    >
      {state}
    </span>
  );
}
