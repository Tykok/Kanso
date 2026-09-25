"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ConflictChooser } from "@/components/inbox/conflict-chooser";
import { groupOf } from "@/components/inbox/copy";
import { InboxRow } from "@/components/inbox/row";
import { InboxTabs } from "@/components/inbox/tabs";
import { TopbarSlot, useReportError } from "@/components/shell/topbar-slot";
import { usePageActions } from "@/components/shell/use-shell-keys";
import { GroupLabel } from "@/components/ui/group-label";
import { Kbd } from "@/components/ui/kbd";
import type { InboxTab, Notification } from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import {
  useInbox,
  useMarkAllRead,
  useMarkRead,
  useMe,
  usePatchTicket,
  useRetryFailedPushes,
  type PatchInput,
} from "@/lib/queries";
import { actionById } from "@/lib/actions";
import { isMac } from "@/lib/platform";
import { canConfigure } from "@/lib/seat";
import { hintFor } from "@/lib/shortcuts";
import { syncQueueHref } from "@/lib/sync-queue-href";
import { useBindings } from "@/lib/use-bindings";
import { useUi } from "@/store/ui";

/**
 * The patch that adopts Notion's version of a field, or nothing.
 *
 * A closed switch rather than a computed key. `PatchInput` names its fields, and a
 * conflict's `field` is a string off a payload — accepting it as a key would let a
 * payload written by some future build send `{ archived: "yes" }` to the server. Only
 * the two free-text scalars are here: a status or a priority coming back from Notion is
 * already refused by the poller's closed vocabulary before a conflict is ever recorded.
 */
function ticketPatchFor(conflict: Notification, value: string): PatchInput | undefined {
  if (conflict.entityType !== "ticket") return undefined;
  switch (conflict.payload.field) {
    case "title":
      return { id: conflict.entityId, title: value };
    case "description":
      return { id: conflict.entityId, description: value };
    default:
      return undefined;
  }
}

/**
 * Screen 14.
 *
 * It used to draw its own copy of the shell — the grid, the sidebar, a context assembled
 * only to feed it, and its own idea of the Notion summary — because the application's
 * frame lived in `app/page.tsx` rather than in a layout. All of that is the layout's now,
 * and what is left is the inbox: four tabs, two groups of rows, and `⇧e` — which is now
 * `inbox.markAllRead` in the registry, claimed here rather than read from a `keydown` of
 * this page's own.
 *
 * It also mounted the import dialog, which `app/page.tsx` mounted too. The shell mounts
 * it once, so the store value can no longer be set on one route and drawn on another.
 */
export default function InboxPage() {
  const router = useRouter();
  const me = useMe();
  const queueHref = syncQueueHref(canConfigure(me.data?.user.instanceRole));
  const { overlay, open, close } = useUi();
  const reportError = useReportError();

  const [tab, setTab] = useState<InboxTab>("all");
  const [conflict, setConflict] = useState<Notification>();

  const inbox = useInbox(tab);
  const markAllRead = useMarkAllRead();
  const markRead = useMarkRead();
  const retryPushes = useRetryFailedPushes();
  const patch = usePatchTicket();


  /**
   * The moment the rows were fetched, which is what every "4 min ago" is measured from.
   *
   * The fetch time rather than a clock read at render: one instant for the whole list,
   * and one that moves only when the data does — otherwise a keystroke anywhere on the
   * page ages the rows under the reader. `refetchInterval` is a minute, which is this
   * column's own granularity anyway. It is also the only reading of "now" a `useMemo`
   * may take: `Date.now()` in there is an impure call, and the lint rule that says so is
   * right — a memo that reads the clock returns a different answer on a replay.
   *
   * Zero before the first fetch resolves, which is the epoch and is never drawn: there
   * are no rows to time until there is data.
   */
  const now = useMemo(() => new Date(inbox.dataUpdatedAt), [inbox.dataUpdatedAt]);

  const rows = inbox.data?.rows ?? [];
  const unread = rows.filter((row) => groupOf(row) === "unread");
  const earlier = rows.filter((row) => groupOf(row) === "earlier");

  const openRow = (notification: Notification) => {
    if (notification.id) markRead.mutate(notification.id);

    if (notification.kind === "conflict") {
      setConflict(notification);
      open("conflict");
      return;
    }
    // Straight to the thing. A notification that cannot be followed to its subject is
    // a sentence about work, not a way into it.
    if (notification.entityType === "ticket" && notification.reference) {
      router.push(`/t/${notification.reference}`);
    } else if (notification.entityType === "project") {
      router.push(`/p/${notification.entityId}`);
    }
  };

  /**
   * `⇧e` marks everything read, and the page supplies the body rather than the key.
   *
   * It used to be this page's own `window` listener testing `event.key === "E"`, with the
   * branch report recording the reason: "`ActionContext` carries no inbox mutation and no
   * router, so an action in the shared registry could not do this". Both halves of that
   * are still true, and neither is a reason for a sixth `keydown`. `inbox.markAllRead` is
   * a registry action whose `when` is "somebody can do this" and whose body is claimed
   * here — so `?` lists the key, the palette can run it, §6.5 can remap it, and the
   * dispatcher stays one listener. See `lib/actions/claims.ts`.
   */
  usePageActions({ "inbox.markAllRead": () => markAllRead.mutate() });

  const { keys } = useBindings();
  const markAllReadKey = hintFor(actionById("inbox.markAllRead"), keys, isMac());

  const counts = inbox.data?.counts ?? { all: 0, assigned: 0, mentions: 0, failures: 0, unread: 0 };

  return (
    <>
      {/*
        * The breadcrumb is gone from here, and it is not missed: it read `All tickets /
        * Inbox`, which claimed the ticket list as this screen's parent. The shell draws
        * `Inbox`, from the route, and the column beside it is how you reach the list.
        *
        * `Mark all read` goes in the bar all the same, because it is this page's control
        * and not the shell's. Never disabled, which is deliberate and matches the
        * drawing: the count it would be gated on is up to a minute stale, and a control
        * that is sometimes dead for reasons the reader cannot see is worse than one whose
        * click occasionally changes nothing. `⇧e` answers unconditionally for the same
        * reason, so the key and the button cannot disagree.
        */}
      <TopbarSlot>
        <span className="flex-1" />
        <button
          type="button"
          className="hover:text-muted-foreground"
          onClick={() => markAllRead.mutate()}
        >
          Mark all read
        </button>
        {markAllReadKey && <Kbd>{markAllReadKey}</Kbd>}
      </TopbarSlot>


      <InboxTabs active={tab} counts={counts} onSelect={setTab} />

      <div className="flex min-h-0 flex-1 flex-col gap-row overflow-y-auto px-6 pb-6">
        {inbox.isError && <div className="empty error">{actionErrorMessage(inbox.error)}</div>}

        {!inbox.isError && rows.length === 0 && (
          <div className="empty flex-col gap-1">
            <span className="text-13 text-foreground">Nothing waiting for you</span>
            <span className="text-12 text-faint">
              Assignments, mentions and refused pushes land here.
            </span>
          </div>
        )}

        {unread.length > 0 && <GroupLabel className="pt-2.5">Unread</GroupLabel>}
        {unread.map((row) => (
          <InboxRow
            key={row.id ?? `push-${String(row.payload.jobId)}`}
            notification={row}
            now={now}
            onOpen={() => openRow(row)}
            onRetry={() =>
              retryPushes.mutate(undefined, {
                onError: (failure) => reportError(actionErrorMessage(failure)),
              })
            }
            onSeeQueue={() => router.push(queueHref)}
          />
        ))}

        {earlier.length > 0 && <GroupLabel>Earlier</GroupLabel>}
        {earlier.map((row) => (
          <InboxRow
            key={row.id ?? `push-${String(row.payload.jobId)}`}
            notification={row}
            now={now}
            onOpen={() => openRow(row)}
            onRetry={() => retryPushes.mutate()}
            onSeeQueue={() => router.push(queueHref)}
          />
        ))}
      </div>

      {overlay === "conflict" && conflict && (
        <ConflictChooser
          notification={conflict}
          // Kanso already won: the poller discarded the Notion edit and queued a
          // corrective push before this row existed. So there is nothing to write —
          // this only stops the inbox asking again.
          onKeepMine={() => {
            if (conflict.id) markRead.mutate(conflict.id);
            close();
          }}
          // An ordinary patch, made here, which then pushes to Notion like any other
          // edit. The mirror's rule is untouched; the person changed their mind.
          onKeepTheirs={(value) => {
            const patched = ticketPatchFor(conflict, value);
            if (patched) patch.mutate(patched);
            else reportError("Kanso cannot apply the Notion value for this field.");
            if (conflict.id) markRead.mutate(conflict.id);
            close();
          }}
          onClose={close}
        />
      )}
    </>
  );
}
