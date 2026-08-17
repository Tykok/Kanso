"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { ConflictChooser } from "@/components/inbox/conflict-chooser";
import { groupOf } from "@/components/inbox/copy";
import { ImportDialog } from "@/components/inbox/import-dialog";
import { InboxRow } from "@/components/inbox/row";
import { InboxTabs } from "@/components/inbox/tabs";
import { OfflineBanner, useOfflineWatch } from "@/components/offline/banner";
import { Sidebar } from "@/components/sidebar";
import { GroupLabel } from "@/components/ui/group-label";
import { Kbd } from "@/components/ui/kbd";
import type { InboxTab, Notification } from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import {
  useInbox,
  useMarkAllRead,
  useMarkRead,
  usePatchTicket,
  usePreferences,
  useRetryFailedPushes,
  useSyncStatus,
  type PatchInput,
} from "@/lib/queries";
import { useActionContext } from "@/lib/use-action-ctx";
import { cn } from "@/lib/utils";
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
 * Its own shell, because the application's shell lives in `app/page.tsx` rather than in
 * a layout — so this route draws the sidebar itself from the same component, with a
 * context built by the same hook. It is not a copy of that page: the inbox has no ticket
 * list, no filter and no view toggle, and the four things it does share (the sidebar, the
 * context, the sync summary, the overlays it opens) are imported.
 */
export default function InboxPage() {
  const router = useRouter();
  const preferences = usePreferences();
  const sync = useSyncStatus();
  const { dialog, overlay, open, close } = useUi();

  const [tab, setTab] = useState<InboxTab>("all");
  const [conflict, setConflict] = useState<Notification>();
  const [error, setError] = useState<string | null>(null);

  const inbox = useInbox(tab);
  const markAllRead = useMarkAllRead();
  const markRead = useMarkRead();
  const retryPushes = useRetryFailedPushes();
  const patch = usePatchTicket();

  useOfflineWatch();

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

  const reportError = useCallback((message: string | null) => setError(message), []);

  // The sidebar needs the one object every action runs against. The inbox has no
  // cursor and no rows of its own to move through, so the list-local half is inert.
  const ctx = useActionContext({
    tickets: [],
    selected: undefined,
    move: () => {},
    startRename: () => {},
    startLink: () => {},
    startUnlink: () => {},
    reportError,
  });

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
   * `⇧e` marks everything read. Page-local rather than an entry in `lib/actions`:
   * `ActionContext` carries no inbox mutation and no router, so an action in the shared
   * registry could not do this — recorded in the branch report rather than worked around.
   */
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (overlay !== "none" || dialog.kind !== "none") return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.target instanceof HTMLElement && /^(INPUT|TEXTAREA|SELECT)$/.test(event.target.tagName)) {
        return;
      }
      // `event.key` for Shift+e is "E": the shift is spelled by the key itself, which is
      // the convention `lib/actions` already uses so nothing carries modifier state.
      if (event.key === "E") {
        event.preventDefault();
        markAllRead.mutate();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [markAllRead, overlay, dialog]);

  const mirrorSummary = !sync.data
    ? ""
    : sync.data.failed.length
      ? `Notion · ${sync.data.failed.length} failed`
      : sync.data.mirrorEnabled
        ? "Notion · up to date"
        : "Notion mirror off";

  const counts = inbox.data?.counts ?? { all: 0, assigned: 0, mentions: 0, failures: 0, unread: 0 };

  return (
    <div
      className={cn(
        "grid h-screen max-[720px]:grid-cols-[1fr]",
        preferences.sidebarVisible ? "grid-cols-[248px_1fr]" : "grid-cols-[1fr]",
      )}
    >
      {preferences.sidebarVisible && (
        <div className="contents max-[720px]:hidden">
          <Sidebar ctx={ctx} syncSummary={mirrorSummary} />
        </div>
      )}

      <div className="flex min-h-0 min-w-0 flex-col">
        <div className="flex items-center gap-3.5 bg-card px-6 py-3 text-12 text-faint">
          <Link href="/" className="hover:text-muted-foreground">
            All tickets
          </Link>
          <span>/</span>
          <span className="text-muted-foreground">Inbox</span>
          <span className="flex-1" />
          {/*
            Never disabled, which is deliberate and matches the drawing: the count it
            would be gated on is up to a minute stale, and a control that is sometimes
            dead for reasons the reader cannot see is worse than one whose click
            occasionally changes nothing. `⇧e` answers unconditionally for the same
            reason, so the key and the button cannot disagree.
          */}
          <button
            type="button"
            className="hover:text-muted-foreground"
            onClick={() => markAllRead.mutate()}
          >
            Mark all read
          </button>
          <Kbd>⇧e</Kbd>
        </div>

        {error && (
          <div className="topbar-error error" role="alert">
            <span>{error}</span>
            <button type="button" aria-label="Dismiss this message" onClick={() => setError(null)}>
              ×
            </button>
          </div>
        )}

        <OfflineBanner />

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
                  onError: (failure) => setError(actionErrorMessage(failure)),
                })
              }
              onSeeQueue={() => router.push("/settings")}
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
              onSeeQueue={() => router.push("/settings")}
            />
          ))}
        </div>
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
            else setError("Kanso cannot apply the Notion value for this field.");
            if (conflict.id) markRead.mutate(conflict.id);
            close();
          }}
          onClose={close}
        />
      )}

      {dialog.kind === "importMap" && <ImportDialog onClose={close} />}
    </div>
  );
}
