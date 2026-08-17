"use client";

import { conflictDetail, type Notification } from "@/lib/api";
import { Backdrop } from "@/components/overlays";
import { Button } from "@/components/ui/button";

/**
 * Screen 15's third state: two versions of one field, and three ways out.
 *
 * Built **over** the mirror's rule, not instead of it. `architecture.md` states the
 * rule — Kanso wins — and the inbound poller has already applied it by the time anyone
 * sees this: the Notion edit was discarded and a corrective push queued. So `Keep mine`
 * writes nothing at all; it acknowledges what already happened. `Keep Notion` is an
 * ordinary patch of the field, made here, in Kanso, which then pushes to Notion like
 * any other edit — the rule is untouched, and the mirror is still not authoritative for
 * one second of it. Per-field merge is the drawings' own backlog ticket and is not this.
 *
 * `Later` leaves the notification unread. That is the whole of "later": the row is still
 * in the inbox, the count still includes it, and nothing has been decided.
 */
export function ConflictChooser({
  notification,
  onKeepMine,
  onKeepTheirs,
  onClose,
}: {
  notification: Notification;
  onKeepMine: () => void;
  onKeepTheirs: (value: string) => void;
  onClose: () => void;
}) {
  const detail = conflictDetail(notification);

  // A conflict whose payload cannot be read is the one case where saying so beats
  // drawing an empty chooser: there is nothing to choose between.
  if (!detail) {
    return (
      <Backdrop onClose={onClose} panelClassName="w-[min(466px,92vw)]">
        <div className="flex flex-col gap-3 p-5">
          <span className="text-13">This conflict was recorded by an older build.</span>
          <span className="text-12 text-muted-foreground">
            Kanso&rsquo;s copy is the one in use — the mirror has already been corrected.
          </span>
          <Button variant="outline" className="self-start" onClick={onClose}>
            Close
          </Button>
        </div>
      </Backdrop>
    );
  }

  return (
    <Backdrop onClose={onClose} panelClassName="w-[min(466px,92vw)]">
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Conflict on ${notification.reference ?? "this item"}, ${detail.field}`}
        className="flex flex-col"
      >
        <div className="flex items-center gap-2 bg-urgent/10 px-4 py-2.5 text-11 text-urgent">
          <span className="size-[7px] rounded-full bg-urgent" />
          Conflict on {notification.reference ?? "this item"} · {detail.field}
        </div>

        <div className="flex flex-col gap-3 p-5">
          <span className="text-13 text-muted-foreground">
            Two versions of the {detail.field}. Choose, or keep both and settle it later.
          </span>

          {/* Mine first, and tinted: it is the one currently in use. */}
          <div className="flex flex-col gap-0.5 rounded-md bg-accent-soft p-3 shadow-[inset_2px_0_0_var(--primary)]">
            <span className="text-11 text-accent-ink">You · in Kanso</span>
            <span className="text-13">{detail.mine}</span>
          </div>

          <div className="flex flex-col gap-0.5 rounded-md bg-background p-3">
            <span className="text-11 text-faint">
              Notion{detail.theirActor ? ` · ${detail.theirActor}` : ""}
              {detail.theirEditedAt ? ` · ${detail.theirEditedAt.slice(11, 16)}` : ""}
            </span>
            <span className="text-13 text-muted-foreground">{detail.theirs}</span>
          </div>

          <div className="mt-1 flex gap-2">
            <Button onClick={onKeepMine}>Keep mine</Button>
            <Button variant="outline" onClick={() => onKeepTheirs(detail.theirs)}>
              Keep Notion
            </Button>
            <Button variant="ghost" onClick={onClose}>
              Later
            </Button>
          </div>
        </div>
      </div>
    </Backdrop>
  );
}
