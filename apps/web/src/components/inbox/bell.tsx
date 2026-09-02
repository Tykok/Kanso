"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { Bell } from "lucide-react";
import { Popover as PopoverPrimitive } from "radix-ui";
import type { Notification } from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import { useInbox, useMarkAllRead, useMarkRead, useRetryFailedPushes } from "@/lib/queries";
import { useReportError } from "@/components/shell/topbar-slot";
import { InboxRow } from "./row";

/**
 * The inbox, as a peek in the top bar.
 *
 * It used to be a row in the sidebar's Views group, which put it in the column with the
 * destinations — and it is not one. An unread count is true of the session rather than of
 * a place, so it belongs beside the other things that are true everywhere: the breadcrumb
 * and the `×`. §4's ruling, and the reason `NAV_ITEMS` keeps `Inbox` as a name with
 * `row: false` rather than dropping it.
 *
 * The popover is a peek and `/inbox` is the place to work. Nothing here is a second
 * implementation of that screen: the rows are `InboxRow` verbatim, the queries are the
 * same three hooks, and the footer's `⤢` hands the reader over the moment they want the
 * four tabs. What the peek deliberately does *not* carry is the tabs themselves and the
 * conflict chooser — a peek is the wrong surface for a choice between two versions of a
 * field, so a conflict row goes to the page that can resolve it.
 */

/**
 * How many rows a peek is. Enough to answer "anything new?" without becoming the screen
 * it links to — `useInbox` returns the tab's whole list, so this is a slice and not a
 * second, narrower request.
 */
const PEEK_ROWS = 8;

export function InboxBell() {
  const router = useRouter();
  const reportError = useReportError();
  const [open, setOpen] = useState(false);

  /**
   * One query behind both the pip and the list. `useUnreadCount` reads exactly this cache
   * entry, so calling it as well would be a second hook for a number already in hand.
   */
  const inbox = useInbox("all");
  const markAllRead = useMarkAllRead();
  const markRead = useMarkRead();
  const retryPushes = useRetryFailedPushes();

  const unread = inbox.data?.counts.unread ?? 0;
  const rows = (inbox.data?.rows ?? []).slice(0, PEEK_ROWS);

  /**
   * The moment the rows were fetched, which is what every "4 min ago" is measured from —
   * `app/(app)/inbox/page.tsx` explains the choice at length and this is the same one, so
   * the peek and the page never age a row differently.
   */
  const now = useMemo(() => new Date(inbox.dataUpdatedAt), [inbox.dataUpdatedAt]);

  const openRow = (notification: Notification) => {
    if (notification.id) markRead.mutate(notification.id);
    // Always closes: a peek's one job is to get you to the thing, so it goes when the
    // click has been answered — even for the kinds below that have nowhere to go, where
    // closing is the only visible acknowledgement the reader gets.
    setOpen(false);

    // A conflict is a decision, not a destination. `ConflictChooser` is a dialog the
    // inbox page mounts, and mounting it from a 380px popover would be a second copy of
    // the one interaction on this screen that must not be got wrong twice.
    if (notification.kind === "conflict") {
      router.push("/inbox");
      return;
    }
    if (notification.entityType === "ticket" && notification.reference) {
      router.push(`/t/${notification.reference}`);
    } else if (notification.entityType === "project") {
      router.push(`/p/${notification.entityId}`);
    }
  };

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={setOpen}>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          data-testid="inbox-bell"
          /*
           * The count is in the name and never in the pixels — see the pip below. A
           * screen reader gets "unread" and not "three unread" for exactly the reason
           * the dot is a dot.
           */
          aria-label={unread > 0 ? "Notifications — unread" : "Notifications"}
          title="Notifications"
          className="relative grid size-6 shrink-0 place-items-center rounded-sm text-muted-foreground hover:bg-accent hover:text-foreground aria-expanded:bg-accent aria-expanded:text-foreground"
        >
          <Bell size={14} aria-hidden />

          {/*
            * A dot, not a number, and the choice is about what a minute-old count can
            * still be trusted to say.
            *
            * `refetchInterval` is 60s, so the figure on screen is up to a minute behind
            * the server. "Something is unread" survives that minute: nothing marks a
            * notification read but the reader, and when they do, `useMarkRead` and
            * `useMarkAllRead` write the cache optimistically, so the dot goes out on the
            * same frame as the click. "Exactly three are unread" does not survive it —
            * a mention arriving thirty seconds ago is already missing from it, and the
            * reader can check: they open the popover, count four rows against a badge
            * saying three, and stop believing the badge. A number also has a zero to get
            * wrong, which `inbox/tabs.tsx` has already ruled on ("a red 0 is an alarm
            * about nothing"); a dot simply is not drawn.
            *
            * The exact counts stay where they can be stood behind: the four tabs on
            * `/inbox` print the numbers the request that fetched them returned.
            *
            * `ring-2 ring-card` so the dot reads as a dot against the bell's own strokes
            * rather than as a thickening of one of them.
            */}
          {unread > 0 && (
            <span
              data-testid="inbox-pip"
              aria-hidden
              className="absolute -top-px -right-px size-[7px] rounded-full bg-primary ring-2 ring-card"
            />
          )}
        </button>
      </PopoverPrimitive.Trigger>

      <PopoverPrimitive.Portal>
        {/*
          * Radix's `Popover`, unmodified, where the hover reveal next door is deliberately
          * not a Radix `Dialog`. The difference is that here every promise the primitive
          * makes is one this surface wants: focus into the content on open because a click
          * asked for it, focus back on the bell when it closes, `Escape` and an outside
          * press to dismiss, and — being non-modal by default — no scroll lock and no
          * `aria-hidden` over the page behind. `mobile-nav.tsx`'s argument for using the
          * library rather than hand-rolling any of that applies in full.
          */}
        <PopoverPrimitive.Content
          align="end"
          sideOffset={6}
          collisionPadding={8}
          data-testid="inbox-peek"
          aria-label="Notifications"
          className="z-50 flex w-[380px] max-w-[92vw] flex-col rounded-panel border border-border bg-popover shadow-float outline-none"
          /*
           * `Escape` closes the peek and does not also leave the page.
           *
           * Radix listens for it on `document` in the capture phase and dismisses unless
           * the default was prevented; `use-shell-keys.ts` listens on `window` in the
           * bubble phase and means "close what is open, *then leave*" — and it cannot
           * see this popover, since it is not one of `useUi`'s overlays. So without this
           * one press would close the peek and send the reader back a page. Stopping
           * propagation rather than preventing the default is the whole point: Radix
           * checks only `defaultPrevented`, so the dismiss still happens.
           */
          onEscapeKeyDown={(event) => event.stopPropagation()}
        >
          <div className="flex items-center gap-3 border-b border-border px-3 py-2 text-11">
            <span className="flex-1 uppercase tracking-wide text-faint">Notifications</span>
            {/*
              * Never disabled, for the reason the page's copy of this control is never
              * disabled: the count it would be gated on is up to a minute stale, and a
              * button that is sometimes dead for reasons the reader cannot see is worse
              * than one whose click occasionally changes nothing.
              *
              * No `⇧e` printed beside it, unlike the page. That chord is page-local until
              * §6 makes it a registry action, and a key printed in chrome that appears on
              * every route would be a promise only one route keeps.
              */}
            <button
              type="button"
              className="text-muted-foreground hover:text-foreground"
              onClick={() => markAllRead.mutate()}
            >
              Mark all read
            </button>
          </div>

          {/*
            * Its own scroller, with `overscroll-contain`: reaching the end of eight rows
            * must not start scrolling the ticket list behind the popover.
            */}
          <div className="flex max-h-[420px] flex-col gap-row overflow-y-auto overscroll-contain p-1.5">
            {inbox.isError && (
              <div className="px-2 py-5 text-center text-12 text-urgent">
                {actionErrorMessage(inbox.error)}
              </div>
            )}

            {!inbox.isError && rows.length === 0 && (
              <div className="flex flex-col gap-1 px-2 py-6 text-center">
                <span className="text-12 text-foreground">Nothing waiting for you</span>
                <span className="text-11 text-faint">
                  Assignments, mentions and refused pushes land here.
                </span>
              </div>
            )}

            {rows.map((row) => (
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
                onSeeQueue={() => {
                  setOpen(false);
                  router.push("/settings");
                }}
              />
            ))}
          </div>

          <div className="flex border-t border-border px-3 py-2">
            <button
              type="button"
              data-testid="inbox-peek-expand"
              className="ml-auto flex items-center gap-1.5 text-11 text-muted-foreground hover:text-foreground"
              onClick={() => {
                setOpen(false);
                router.push("/inbox");
              }}
            >
              <span aria-hidden>⤢</span>
              All notifications
            </button>
          </div>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  );
}
