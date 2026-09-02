"use client";

import { TrashView } from "@/components/trash/view";

/**
 * Screen 26, at a route of its own.
 *
 * It used to draw its own page-width layout with a `Back` link and its own sign-in
 * redirect, because the application's frame lived in `app/page.tsx` and this route was
 * not inside it. That is the whole of "quand je clique sur Trash la sidebar disparaît":
 * nothing disappeared, the destination never had one.
 *
 * Now it is inside `app/(app)/`, so the column, the `Trash` crumb, the `×` and the
 * sign-in gate are all the shell's. What is left is the panel — which is what this file
 * was always about.
 *
 * `overflow-y-auto` because the shell's grid is `h-screen`: the document itself no longer
 * scrolls, the content column does, and a page that forgot to say so would clip.
 */
export default function TrashPage() {
  return (
    <div className="mx-auto flex w-full max-w-[760px] flex-col gap-5 overflow-y-auto px-5 pt-6 pb-16">
      <header className="border-b border-border pb-4">
        <h1 className="m-0 text-15 font-medium tracking-tight">Trash and archives</h1>
      </header>

      {/* `overflow-hidden` so the header strip's own ground stops at the panel's corner
          rather than squaring it off — the strip is a full-bleed band, not a padded row. */}
      <div className="flex flex-col overflow-hidden rounded-panel border border-border bg-card shadow-flat">
        <TrashView />
      </div>
    </div>
  );
}
