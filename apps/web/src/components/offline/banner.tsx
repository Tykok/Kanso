"use client";

import { Button } from "@/components/ui/button";
import { useOffline } from "@/store/offline";
import { cn } from "@/lib/utils";

/**
 * Screen 15's second state: the writes that have not reached the server, listed.
 *
 * Listed, not counted. "4 changes queued" alone asks the reader to trust that the right
 * four are in there, and the one thing somebody who has been writing offline wants to
 * know is whether the edit they care about is among them. So every row prints its
 * reference and what it did.
 *
 * Drawn only when there is something in the queue. An always-present "you are online"
 * strip is a line of chrome that says nothing on every screen it appears on.
 */
export function OfflineBanner({ className }: { className?: string }) {
  const { online, writes, durable, flush, retry, discard } = useOffline();

  if (writes.length === 0) return null;

  const rejected = writes.filter((write) => write.state === "rejected");

  return (
    <section
      data-testid="offline-banner"
      className={cn("flex flex-col bg-card", className)}
      aria-label="Queued writes"
    >
      <div
        className={cn(
          "flex items-center gap-2 px-4 py-2.5 text-11",
          online ? "bg-accent text-muted-foreground" : "bg-warning/15 text-status-progress",
        )}
        role="status"
      >
        <span className={cn("size-[7px] rounded-full", online ? "bg-status-review" : "bg-status-progress")} />
        {online ? "Reconnected — sending what was queued" : "Offline — you can keep writing"}
      </div>

      <div className="flex flex-col gap-3 p-5">
        <span className="text-15 font-medium">
          {writes.length} {writes.length === 1 ? "change" : "changes"} queued
        </span>

        <div className="flex flex-col gap-0.5 text-12">
          {writes.map((write) => (
            <div
              key={write.id}
              data-testid="queued-write"
              className="grid h-[30px] grid-cols-[66px_1fr_auto] items-center gap-2.5 rounded-sm bg-background px-2.5"
            >
              <span className="font-mono text-11 text-faint">{write.reference}</span>
              <span className="truncate text-muted-foreground">{write.summary}</span>
              {write.state === "rejected" ? (
                <span className="flex items-center gap-2">
                  <span className="text-11 text-urgent uppercase tracking-wide" title={write.error}>
                    refused
                  </span>
                  <Button size="xs" variant="ghost" onClick={() => void retry(write.id)}>
                    Retry
                  </Button>
                  <Button size="xs" variant="ghost" onClick={() => void discard(write.id)}>
                    Discard
                  </Button>
                </span>
              ) : (
                <span className="text-11 text-status-progress uppercase tracking-[0.06em]">queued</span>
              )}
            </div>
          ))}
        </div>

        {/*
         * Two sentences, and the second only when it is true. The promise in the first
         * is the queue's actual guarantee — order is preserved per actor — and making
         * it while the disk is a `Map` that dies with the tab would be the interface
         * lying about the one thing this feature is for.
         */}
        <span className="text-11 text-faint">
          {durable
            ? "Sent automatically when the network returns. The order is kept."
            : "This browser gave Kanso no storage, so the queue will not survive a reload."}
        </span>

        {rejected.length > 0 && (
          <span className="text-11 text-urgent">
            {rejected[0].error ?? "The server refused a write."} Everything behind it is waiting.
          </span>
        )}

        {/*
          Offered whether or not this store thinks the network is back, because neither
          signal it has is trustworthy: `navigator.onLine` is false only when the OS is
          certain, and `online` here is the last request's outcome. A captive portal that
          has just been signed into leaves both saying the wrong thing, and one button is
          how somebody gets out of that without reloading.
        */}
        <Button size="sm" variant="outline" className="self-start" onClick={() => void flush()}>
          Send now
        </Button>
      </div>
    </section>
  );
}
