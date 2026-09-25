"use client";

import { useCallback, useEffect, useRef, useSyncExternalStore } from "react";
import { PanelLeft } from "lucide-react";
import { Sidebar } from "@/components/sidebar";
import type { ActionContext } from "@/lib/actions";
import { usePreferences } from "@/lib/queries";
import { cn } from "@/lib/utils";
import { useUi } from "@/store/ui";
import { isRevealed, NOTHING_HELD, retractsOnLeave, sameHold, type RevealHold } from "./reveal";

/**
 * The column, and how much window it is allowed.
 *
 * Three shells each carried their own copy of this test and its grid, which is how a
 * one-line preference became a three-line bug surface. It is one test now, in one place,
 * and `AppShell` composes the grid from [useSidebarPinned] so the column and the template
 * that holds it cannot disagree about whether there is one.
 *
 * Three modes:
 *
 *   - **`pinned`** — the 248px grid column, exactly as before.
 *   - **`hover`** — no column; a 12px hot zone down the left edge, and entering it slides
 *     the same `<Sidebar>` in *over* the content.
 *   - **`hidden`** — no column and no hot zone; the top bar's `PanelLeft` slides the same
 *     panel in. A mode with no way back is the bug this pass exists to remove.
 *
 * **Not a Radix `Dialog`, where `mobile-nav.tsx` is one, and the difference is the whole
 * design.** That file argues at length that a hand-rolled overlay cannot promise what a
 * modal owes: initial focus, a Tab trap, `aria-hidden` on everything behind it, a locked
 * scroll. Every one of those is right for the drawer, because the drawer *is* the reader's
 * attention — it hides the page behind a scrim and exists to take them off it. Three of
 * the four are actively wrong here:
 *
 *   - **Initial focus.** A panel that appears because a pointer grazed the left edge must
 *     not take the caret out of the title somebody is typing. Radix's `Dialog` focuses
 *     its content on open, and a peek that steals focus is worse than no peek.
 *   - **`aria-hidden` behind it.** This panel has no scrim and the content behind it stays
 *     readable and clickable — a pointer *leaving through the content* is how it retracts.
 *     Hiding the whole application from assistive technology to reveal a nav column would
 *     be a lie about what is on screen.
 *   - **The locked scroll.** The page behind must keep scrolling; `overscroll-contain`
 *     below is the only thing this panel is allowed to say about scrolling, and it says
 *     it about its own list.
 *
 * The fourth promise — that focus cannot escape into a page behind a visible overlay — is
 * kept, and kept without a trap, because a trap is the wrong shape for something the
 * reader never asked to enter: **focus leaving the panel retracts it.** So Tab from the
 * last row moves on into the top bar and the overlay is gone in the same commit; there is
 * never a moment where focus sits behind something drawn over it. That also answers the
 * other half — the panel stays in the tab order while it is closed, deliberately, because
 * "opens on `focus-within` so Tab reaches it" is not possible for an `inert` panel. It is
 * invisible for zero frames: the focus that reaches it is what reveals it.
 *
 * **`<html data-sidebar>` is still unspent, and this slice leaves it that way.**
 * `PREFERENCE_BOOTSTRAP_SCRIPT` stamps the mode before the first pixel so that a paint
 * cannot guess `pinned` and hydrate into `hover`. The paint it was protecting does not
 * exist: `useMe` carries no `initialData`, so `AppShell`'s first render is `isLoading` and
 * returns `<BrandSplash>` — the grid is never drawn until the mode is known. Spending the
 * attribute would mean expressing this template in CSS *as well as* in React, which is
 * the two-sources-for-one-layout shape the three shells this file replaced were made of.
 * It becomes worth spending the day `/api/me` is prefetched or the splash goes away, and
 * that is the condition to check rather than the attribute's existence.
 */
export function useSidebarPinned(): boolean {
  return usePreferences().sidebarMode === "pinned";
}

/**
 * Whether the reveal is out, module-level rather than in a context or in `useUi`.
 *
 * The panel is rendered by this file and the button that opens it by `topbar.tsx`, two
 * subtrees apart, and their only common ancestor — `app-shell.tsx` — is where a provider
 * would have to go and is another slice's file. `useUi` was the other candidate and is
 * worse than it looks: it is a module store sixty-five files read, it survives every route
 * change, and a peek left open in it would become application-wide state with a lifetime
 * nobody asked for — which is the exact shape of the bug where a dialog set on one screen
 * appeared on another. This is one record, three booleans wide, subscribed to by the two
 * components that draw it.
 */
let held: RevealHold = NOTHING_HELD;
const watchers = new Set<() => void>();

function hold(patch: Partial<RevealHold>) {
  const next = { ...held, ...patch };
  // `sameHold` and not a reference check: every next value is a fresh object, so without
  // this a `mouseenter` on the panel after one on the hot zone would re-render both
  // components to say what they already said.
  if (sameHold(held, next)) return;
  held = next;
  for (const watcher of watchers) watcher();
}

const subscribe = (watcher: () => void) => {
  watchers.add(watcher);
  return () => {
    watchers.delete(watcher);
  };
};

/** Never out on the server: there is no pointer, and the first paint must not guess one. */
const useHold = () => useSyncExternalStore(subscribe, () => held, () => NOTHING_HELD);

/**
 * The button in the top bar, which is where the reader whose pointer has nothing to hover
 * — a touch screen, a keyboard, a 12px sliver they have not discovered — gets in.
 *
 * Kept for `focus()` on `Escape`: the panel retracts and the focus that was inside it
 * lands on the control that opens it again, rather than on `<body>`, which would restart
 * Tab from the top of the document.
 */
let triggerNode: HTMLButtonElement | null = null;

/** Does a control inside the panel have a popup open right now? */
const holdsAnOpenPopup = (panel: HTMLElement | null) =>
  panel !== null && panel.querySelector('[aria-expanded="true"]') !== null;

/** Names the panel for the top bar button's `aria-controls`. */
const REVEAL_ID = "sidebar-reveal";

export function SidebarFrame({ ctx }: { ctx: ActionContext }) {
  const mode = usePreferences().sidebarMode;

  /*
   * `contents` so this wrapper is invisible to the grid — `<Sidebar>` itself lands in the
   * 248px column — and `max-[720px]:hidden` so only *this* copy disappears under 720px.
   * `MobileNavDrawer` renders the same component in a drawer there, and `<Sidebar>` does
   * not hide itself: it must not, since it is reused inside that drawer and inside the
   * reveal below.
   */
  if (mode === "pinned") {
    return (
      <div className="contents max-[720px]:hidden">
        <Sidebar ctx={ctx} />
      </div>
    );
  }

  return <SidebarReveal mode={mode} ctx={ctx} />;
}

/**
 * The temporary column, for `hover` and for `hidden` alike.
 *
 * One component for both, because they differ by exactly one thing — whether there is a
 * hot zone — and drawing the panel twice is how the two modes would come to retract
 * differently.
 */
function SidebarReveal({
  mode,
  ctx,
}: {
  mode: "hover" | "hidden";
  ctx: ActionContext;
}) {
  const open = isRevealed(useHold());
  const panel = useRef<HTMLDivElement>(null);
  const { overlay, dialog } = useUi();

  /**
   * Something is drawn over the peek — a dialog the column's own `+ New team` opened, the
   * palette, the help overlay. While that is true the panel holds whatever it has: the
   * trigger the dialog was opened from lives inside it, and retracting would unmount the
   * node `DialogFrame` restores focus to when it closes.
   */
  const covered = overlay !== "none" || dialog.kind !== "none";

  const dismiss = useCallback(() => hold(NOTHING_HELD), []);

  /**
   * `Escape` retracts it, from anywhere — not only when focus is inside, since the
   * commonest way to have a peek open is to have hovered into it with the caret still in
   * the list behind.
   *
   * The capture phase, and stopped. `use-shell-keys.ts` listens for `Escape` on `window`
   * in the bubble phase and its meaning there is "close what is open, then *leave the
   * page*". A peek is what is open, so it has to be closed one layer further out — taking
   * the event on the way down is what makes the order come out right without duplicating
   * that file's rule. It stands down entirely while [covered], because then the dialog is
   * what is open and one key must not do two things.
   */
  useEffect(() => {
    if (!open || covered) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      const inside = panel.current?.contains(document.activeElement) ?? false;
      dismiss();
      // Only if the focus was in the panel that just left. Pulling it out of a text field
      // elsewhere on the page would make `Escape` steal the caret.
      if (inside) triggerNode?.focus();
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, [open, covered, dismiss]);

  /**
   * A press outside retracts it, which is what a panel opened by a click owes — in
   * `hidden` there is no hot zone to leave, so without this the button's own second press
   * would be the only way back out.
   *
   * `pointerdown` and not `click`, so the panel is gone before the press it was hiding
   * lands. The popup guard is the same one the leave handler needs and for the same
   * reason: a row's `⋯` is portalled to the body, so a press on one of its entries is a
   * press outside this panel.
   */
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target;
      if (!(target instanceof Node)) return;
      if (panel.current?.contains(target)) return;
      if (triggerNode?.contains(target)) return;
      if (holdsAnOpenPopup(panel.current)) return;
      dismiss();
    };
    window.addEventListener("pointerdown", onPointerDown);
    return () => window.removeEventListener("pointerdown", onPointerDown);
  }, [open, dismiss]);

  return (
    <>
      {/*
        * The 12px hot zone, `hover` only. `hidden` deliberately has none: the whole
        * difference between the two modes is whether the left edge of the window is
        * live, and a mode that reveals on a graze is not hidden.
        *
        * `aria-hidden` because it is a pointer target and nothing else. There is nothing
        * to announce — the nav it reveals is in the reading order either way, and the
        * top bar's button is the accessible way in.
        */}
      {mode === "hover" && (
        <div
          data-testid="sidebar-hot-zone"
          aria-hidden
          className="fixed inset-y-0 left-0 z-30 w-3 max-[720px]:hidden"
          onMouseEnter={() => hold({ pointer: true })}
        />
      )}

      {/*
        * Mounted whether it is out or not, and translated rather than unmounted.
        *
        * Two things need it mounted. A transform animates and a mount does not, so this
        * is what makes the reveal a slide instead of an appearance; and the panel has to
        * be reachable by Tab while it is closed, or "opens on `focus-within`" is
        * unreachable — see the note at the top of this file.
        *
        * `overflow-y-auto` on this element rather than on the `<aside>` inside it, which
        * is what `MobileNavDrawer` does with the same component for the same reason: the
        * aside sizes to its content, and the scroller has to be the thing with a height.
        * `overscroll-contain` so reaching the end of the nav does not start scrolling the
        * ticket list behind it — the page's own scroll is untouched, which is the other
        * half of what this panel owes it.
        */}
      <div
        ref={panel}
        id={REVEAL_ID}
        data-testid="sidebar-reveal"
        data-open={open}
        className={cn(
          // `pt-14` is the top bar's 56px, kept clear — the same clearance
          // `MobileNavDrawer` gives the `☰` it slides out from, and for the same reason.
          // Without it this panel's own brand seal landed on exactly the 24px square the
          // bar's `PanelLeft` occupies, so the control that had just opened the panel was
          // *underneath* it: `aria-expanded="true"`, an `aria-label` reading "Hide the
          // navigation", and a press that could never arrive. Under `hidden` that press is
          // the reader's most obvious way back out, and it retried for 45 seconds against
          // the seal instead. The clearance is what the button is raised *over*; see
          // `SidebarRevealButton`.
          "fixed inset-y-0 left-0 z-30 flex w-[248px] flex-col overflow-y-auto overscroll-contain bg-card pt-14 shadow-float transition-transform duration-150 max-[720px]:hidden",
          open ? "translate-x-0" : "-translate-x-full",
        )}
        onMouseEnter={() => hold({ pointer: true })}
        onMouseLeave={(event) => {
          if (covered) return;
          // Past the panel's right edge a popover is no longer inside its footprint, so
          // the geometry cannot answer for it: a team row's `⋯` opens `align="end"`, to
          // the right of a 248px column. The trigger is still here and still says
          // `aria-expanded="true"`, so that is the question to ask.
          if (holdsAnOpenPopup(event.currentTarget)) return;
          const box = event.currentTarget.getBoundingClientRect();
          const leaving = retractsOnLeave(
            { x: event.clientX, y: event.clientY },
            { right: box.right },
            { height: window.innerHeight },
          );
          if (leaving) hold({ pointer: false });
        }}
        // `onFocus`/`onBlur` are React's `focusin`/`focusout`, which bubble — so this is
        // `focus-within` without a stylesheet, and it can distinguish focus *leaving* the
        // panel from focus moving between two rows inside it, which `:focus-within`
        // cannot be asked about from JavaScript.
        onFocus={() => hold({ focus: true })}
        onBlur={(event) => {
          if (event.currentTarget.contains(event.relatedTarget)) return;
          // Into a popover this panel opened. `relatedTarget` is in the portal, not in
          // here, so `contains` says "gone" about focus that is still the column's.
          if (holdsAnOpenPopup(event.currentTarget)) return;
          hold({ focus: false });
        }}
      >
        {/* A peek's one job is to take you somewhere, so it goes when it has. This is the
            prop the mobile drawer passes for the same reason; the pinned column leaves it
            unset because it has nothing to close. */}
        <Sidebar ctx={ctx} onNavigate={dismiss} />
      </div>
    </>
  );
}

/**
 * `PanelLeft` in the top bar, shown whenever the column is not pinned.
 *
 * **It reveals; it does not pin.** The spec says both of those about this button in two
 * different paragraphs, and they cannot both be true of one control: §3's `hidden` bullet
 * has it "open the same temporary overlay", and the paragraph below it has it "pin the
 * column back". Revealing is the reading kept, because pinning is the one that leaves a
 * reader worse off — under `hidden` it is their only way in, and a way in that
 * permanently changes the mode is not a way in, it is a mode change wearing a peek's
 * clothes. So this button always means "show me the column", in both modes, and the
 * `PanelLeft` *inside* the panel is the one that decides how the column is anchored. One
 * icon, two places, one meaning each.
 *
 * `hidden` is still never written by a click, which is the invariant that paragraph was
 * really protecting: the two buttons move between `pinned` and `hover` and nowhere else.
 */
export function SidebarRevealButton() {
  const mode = usePreferences().sidebarMode;
  const open = isRevealed(useHold());

  if (mode === "pinned") return null;

  return (
    <button
      type="button"
      // Assigning module state from a ref callback rather than holding it in a `useRef`
      // here: `SidebarReveal` is the one that needs the node, and it is not this
      // component's parent, its child or its sibling in any tree either of them can see.
      ref={(node) => {
        triggerNode = node;
        return () => {
          triggerNode = null;
        };
      }}
      data-testid="sidebar-reveal-trigger"
      aria-expanded={open}
      aria-controls={REVEAL_ID}
      aria-label={open ? "Hide the navigation" : "Show the navigation"}
      title="Navigation"
      // `relative z-40`, above the panel's `z-30`: it is a toggle, and the panel it
      // toggles is drawn over the bar. The panel keeps `pt-14` clear for it, so what this
      // is raised over is empty surface rather than the panel's own seal — one icon in one
      // place, still, and now a second press that lands.
      className="-ml-1 relative z-40 grid size-6 shrink-0 place-items-center rounded-sm text-muted-foreground hover:bg-accent hover:text-foreground aria-expanded:bg-accent aria-expanded:text-foreground max-[720px]:hidden"
      onClick={() => (open ? hold(NOTHING_HELD) : hold({ button: true }))}
    >
      <PanelLeft size={14} aria-hidden />
    </button>
  );
}
