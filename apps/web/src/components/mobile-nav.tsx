"use client";

import { useState } from "react";
import { Dialog as DialogPrimitive } from "radix-ui";
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
 * differs from the desktop column it also renders in. `onNavigate` closes the
 * drawer on a scope change but not on a row action (rename, archive, the `+`
 * buttons): a real modal that hides the page behind it must not stay open once a
 * row has sent you somewhere on that page.
 *
 * `role="dialog"`, `aria-modal`, the initial focus, the Tab trap and the restore
 * to the `☰` trigger on close all come from Radix's `Dialog` primitive — the same
 * one `ui/dialog.tsx` builds on — rather than from a hand-rolled effect here. The
 * previous version set `aria-modal="true"` on a plain `<div>` and kept none of
 * those promises: nothing moved focus in, nothing trapped Tab, nothing outside was
 * hidden from assistive technology, so a keyboard or screen-reader user pressing
 * ☰ had focus stay on the button while being told everything behind it no longer
 * existed. `dialogs/field.tsx`'s `DialogFrame` was the other candidate but says of
 * itself that "a full focus trap is still missing" — it only handles initial focus
 * and restore, not the trap — so it would have repeated the gap rather than closed
 * it. Radix's `Content` traps for real.
 *
 * The `☰` button is wrapped in `Dialog.Trigger` rather than left as a plain button
 * with `onClick={() => setOpen(true)}`: Radix's own close-focus restore
 * (`onCloseAutoFocus`, wired inside `Dialog.Content`) only knows to refocus the
 * element it tracks as `context.triggerRef` — the node under `Dialog.Trigger` — so
 * a button outside that relationship never gets the focus back, silently dropping
 * it on `<body>` instead. `Trigger` also folds in `aria-haspopup`, `aria-expanded`
 * and `aria-controls` for free.
 *
 * The scrim is `rgb(24 24 32 / 32%)`, not the app's usual `bg-black/34` overlay —
 * `Écrans 5` states this exact value, and it is not one token: the composer's own
 * scrim is the same hue at 22%, a different alpha for a different surface.
 */
export function MobileNavDrawer({ ctx }: { ctx: ActionContext }) {
  const [open, setOpen] = useState(false);

  return (
    <DialogPrimitive.Root open={open} onOpenChange={setOpen}>
      <DialogPrimitive.Trigger asChild>
        <button
          type="button"
          data-testid="mobile-nav-trigger"
          className="hidden -ml-2.5 size-11 shrink-0 place-items-center rounded-md text-foreground hover:bg-accent max-[720px]:grid"
          aria-label="Open navigation"
        >
          ☰
        </button>
      </DialogPrimitive.Trigger>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          data-testid="mobile-nav-scrim"
          className="fixed inset-0 z-30 hidden max-[720px]:block"
          style={{ background: "rgb(24 24 32 / 32%)" }}
        />
        <DialogPrimitive.Content
          id="mobile-nav-drawer"
          aria-label="Navigation"
          className="fixed inset-y-0 left-0 z-30 hidden w-[288px] flex-col overflow-y-auto bg-card pt-[44px] shadow-float outline-none max-[720px]:flex"
        >
          <Sidebar ctx={ctx} onNavigate={() => setOpen(false)} />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
