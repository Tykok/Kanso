"use client";

import { Fragment, type Ref } from "react";
import { InboxBell } from "@/components/inbox/bell";
import { MobileNavDrawer } from "@/components/mobile-nav";
import type { ActionContext } from "@/lib/actions";
import { SearchButton } from "./search-button";
import { SidebarRevealButton } from "./sidebar-frame";

/**
 * The one bar above the content: where you are, and how to leave.
 *
 * Four routes used to draw their own header with a `Back` button in it, and three shells
 * drew three more. This is the only one now, which is what makes the `×` a single
 * behaviour rather than four links that happened to look alike.
 *
 * Two of its controls take no props and are not the shell's to configure. Both read what
 * they need from a query and draw nothing when there is nothing to draw:
 * `SidebarRevealButton` is absent while the column is pinned, and `InboxBell` is on every
 * route because an unread count is true of the session rather than of a place — which is
 * the whole of why the inbox left the column for this bar.
 *
 * `SearchButton` joins them on the same argument: the palette is the search, and until
 * this it had one door that needed a chord and one buried in the brand menu, which on a
 * phone amounts to none.
 */
export function Topbar({
  ctx,
  syncSummary,
  crumbs,
  slotRef,
  onLeave,
  canLeave,
}: {
  ctx: ActionContext;
  syncSummary: string;
  /** From `breadcrumbOf`. Empty at `/`, where the list names itself in its own heading. */
  crumbs: string[];
  /** Where `<TopbarSlot>` renders the page's own controls. */
  slotRef: Ref<HTMLDivElement>;
  onLeave: () => void;
  /**
   * False at `/` alone. The `×` says "this is somewhere you went", and offering it on the
   * home screen would be a control whose only honest behaviour is to do nothing.
   */
  canLeave: boolean;
}) {
  return (
    <div className="flex items-center gap-2.5 bg-card px-5 py-3 text-12 text-faint max-[720px]:px-4 max-[720px]:py-2">
      <MobileNavDrawer ctx={ctx} syncSummary={syncSummary} />

      {/* Beside the `☰` and to the left of the trail, which is where the column it
          reveals would begin. Absent while the column is pinned: there is nothing to
          reveal, and a control whose only honest behaviour is to do nothing is the same
          mistake as a `×` on the home screen. */}
      <SidebarRevealButton />

      {/*
        * A `nav`, and the last crumb is not a link.
        *
        * Nothing in this trail is clickable at all, which is deliberate: the crumbs a
        * reader could climb are already rows in the column beside them, and the one
        * crumb that had a link — `Tickets /` above the documents — pointed at a parent
        * `/docs` does not have. So the trail says where you are and the `×` is how you
        * leave; the column is how you go somewhere else.
        */}
      {crumbs.length > 0 && (
        <nav
          aria-label="Breadcrumb"
          // Not on a phone. Every route that draws a trail also names itself in its own
          // heading a few pixels below — which is where the list's `<h1>` went in this
          // pass — so on a 390px screen the trail is the same words twice, and the copy
          // that has to share a line with `☰`, the bell and `×` is the one that gets
          // truncated to `Core / …`. The `☰` beside it reaches everything the trail names.
          className="flex min-w-0 items-center gap-2.5 max-[720px]:hidden"
        >
          {crumbs.map((crumb, index) => (
            <Fragment key={`${index}-${crumb}`}>
              {index > 0 && <span aria-hidden>/</span>}
              <span
                data-testid="breadcrumb-crumb"
                className={
                  index === crumbs.length - 1 ? "truncate text-muted-foreground" : "truncate"
                }
              >
                {crumb}
              </span>
            </Fragment>
          ))}
        </nav>
      )}

      {/* `flex-1` here rather than a spacer of its own, so a page that puts nothing in
          the bar still pushes the `×` to the right end. */}
      <div ref={slotRef} className="flex min-w-0 flex-1 items-center gap-3" />

      {/* The right cluster, in the order the reader leaves the page: where else to go,
          what arrived, then the way out. Unlike the slot, none of the three is a page's
          to withhold. */}
      <SearchButton />
      <InboxBell />

      {canLeave && (
        <button
          type="button"
          data-testid="shell-leave"
          // The same word the key does, so the two are legible as one gesture. `esc` is
          // in the title rather than beside the glyph: a `<kbd>` on every route would be
          // a keyboard lesson repeated on every screen.
          aria-label="Leave this page"
          title="Leave — esc"
          className="-mr-1.5 flex size-6 shrink-0 items-center justify-center rounded-sm text-muted-foreground hover:bg-accent hover:text-foreground"
          onClick={onLeave}
        >
          ×
        </button>
      )}
    </div>
  );
}
