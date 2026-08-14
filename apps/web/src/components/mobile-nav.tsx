"use client";

import type { ActionContext } from "@/lib/actions";
import { Sidebar } from "./sidebar";

/**
 * The maintainer's ruling on mobile navigation: `Écrans 5`'s hamburger opening a
 * 288px off-canvas drawer, not the three-tab bar `Kanso - Mobile.dc.html` draws
 * throughout — that file's third tab leads to Documents, a feature this branch
 * defers (see `task-9-mobile-reference.md`'s RULING). 288px, not the desktop aside's
 * 248px: a deliberate, unreconciled difference the reference document calls out
 * rather than something to quietly align.
 *
 * Reuses `<Sidebar>` verbatim rather than drawing a second nav tree: same rows, same
 * actions, same `ctx` — only the frame around it (width, scrim, top clearance)
 * differs from the desktop column it also renders in.
 *
 * The scrim is `rgb(24 24 32 / 32%)`, not the app's usual `bg-black/34` overlay —
 * `Écrans 5` states this exact value, and it is not one token: the composer's own
 * scrim is the same hue at 22%, a different alpha for a different surface.
 */
export function MobileNavDrawer({
  ctx,
  syncSummary,
  onClose,
}: {
  ctx: ActionContext;
  syncSummary: string;
  onClose: () => void;
}) {
  return (
    <div
      id="mobile-nav-drawer"
      className="fixed inset-0 z-30 hidden max-[720px]:block"
      role="dialog"
      aria-modal="true"
      aria-label="Navigation"
    >
      <div
        className="absolute inset-0"
        style={{ background: "rgb(24 24 32 / 32%)" }}
        onClick={onClose}
      />
      <div className="absolute inset-y-0 left-0 flex w-[288px] flex-col overflow-y-auto bg-card pt-[44px] shadow-float">
        <Sidebar ctx={ctx} syncSummary={syncSummary} />
      </div>
    </div>
  );
}
