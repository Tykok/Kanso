"use client";

import type { Notification } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { relativeTime, rowCopy } from "./copy";

/**
 * The list's own grid, drawn once: dot, reference, sentence, time.
 *
 * `132px` for the time and `78px` for the reference are the drawing's own columns. The
 * middle one is `1fr` and every long string in it truncates — a sentence that wraps
 * would make the rows different heights, and the drawing's list is even.
 */
const COLS = "grid-cols-[20px_78px_1fr_132px]";

/**
 * A dot, or nothing.
 *
 * Three states in one glyph: urgent for a refused push, the accent for anything else
 * unread, and an empty cell once read — which is what puts the read rows visually
 * behind the unread ones without a second column or a border.
 */
function UnreadDot({ notification }: { notification: Notification }) {
  if (notification.readAt) return <span />;
  return (
    <span
      className={cn(
        "size-[7px] shrink-0 rounded-full",
        notification.kind === "sync_failed" ? "bg-urgent" : "bg-primary",
      )}
      // Not decoration: it is the only thing saying the row is unread, and the
      // grouping heading above it is a heading, not a per-row statement.
      aria-label={notification.kind === "sync_failed" ? "Unread failure" : "Unread"}
      role="img"
    />
  );
}

export function InboxRow({
  notification,
  now,
  onOpen,
  onRetry,
  onSeeQueue,
}: {
  notification: Notification;
  /** Passed in so every row in one render is timed against the same instant. */
  now: Date;
  onOpen: () => void;
  onRetry: () => void;
  onSeeQueue: () => void;
}) {
  const copy = rowCopy(notification);
  const unread = !notification.readAt;
  const failure = notification.kind === "sync_failed";

  return (
    <div
      data-testid="inbox-row"
      data-kind={notification.kind}
      data-unread={unread}
      className={cn(
        "grid items-start gap-3.5 rounded-md px-3 py-2.5 text-left",
        unread ? "bg-card" : "text-muted-foreground",
        // A 2px inset bar rather than a red fill: the row still has to be readable,
        // and the drawing marks the failure at the edge.
        failure && unread && "shadow-[inset_2px_0_0_var(--urgent)]",
        COLS,
      )}
    >
      <UnreadDot notification={notification} />

      <span className="pt-0.5 font-mono text-11 text-faint">{notification.reference ?? ""}</span>

      <div className="flex min-w-0 flex-col gap-0.5">
        {/*
         * A button, not a div with a click handler: the whole point of the inbox is to
         * get from a sentence to the thing it is about, and that has to be reachable
         * from the keyboard like every other destination in the app.
         */}
        <button
          type="button"
          className="min-w-0 text-left"
          onClick={onOpen}
          aria-label={`${copy.sentence}${notification.reference ? ` — ${notification.reference}` : ""}`}
        >
          <span className={cn("block truncate", unread && "font-medium text-foreground")}>
            {copy.sentence}
          </span>
        </button>

        {copy.detail && (
          <span
            className={cn(
              "text-12",
              // A failure's reason is the actionable part and gets two lines; every
              // other detail is a title and truncates to keep the rows even.
              failure ? "text-muted-foreground" : "truncate text-muted-foreground",
            )}
          >
            {copy.detail}
          </span>
        )}

        {failure && (
          <div className="mt-1.5 flex gap-2">
            <Button size="xs" onClick={onRetry}>
              Retry
            </Button>
            <Button size="xs" variant="outline" onClick={onSeeQueue}>
              See the queue
            </Button>
          </div>
        )}
      </div>

      <time
        className="pt-0.5 text-right text-11 text-faint"
        dateTime={notification.createdAt}
        title={notification.createdAt}
      >
        {relativeTime(notification.createdAt, now)}
      </time>
    </div>
  );
}
