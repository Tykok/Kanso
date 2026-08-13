"use client";

import { useCallback, useRef, useState } from "react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

export type MenuItem = {
  id: string;
  label: string;
  /**
   * The key that fires the same action from anywhere. Shown against the entry because
   * the menus are where a keyboard-first application's keyboard is discovered: nobody
   * reads the help overlay to find out that `e` renames.
   */
  hint?: string;
  /** Last, detached, never the default choice. */
  danger?: boolean;
  onSelect: () => void;
};

/**
 * The dropdown every mouse path in the application goes through, now on Radix: a row's
 * `⋯`, the sidebar's team and project rows, the top bar's `New`, the brand block's
 * account menu, and a ticket's status and priority pills — the last two through
 * `asChild`, which makes the pill itself the trigger.
 *
 * The signature is unchanged, so all seven call sites are untouched but the two pills,
 * which stop wrapping the menu in a box and hand it their own `<button>` instead.
 *
 * `role="menu"` sits on the list of entries, not on the popover — the same shape the
 * hand-written version had, for the same reason. A `menu` may only own `menuitem`,
 * `menuitemradio`, `menuitemcheckbox`, `group` and `separator`; the header and the
 * footer are neither, and assistive technology is free to drop whatever else it finds
 * inside one. What would be dropped is the identity block, the one thing that answers
 * "who am I signed in as", so it stays a sibling of the list. Radix fixes `role="menu"`
 * on its content element, so the route taken here is the first of the three the design
 * document lists: `role="presentation"` overrides the role on the content, and a plain
 * `<div role="menu">` inside it holds the entries and carries the accessible name.
 * That wrapper was the risk in this file — Radix drives focus, typeahead and arrow
 * navigation through two collections — and it turned out to cost nothing: both
 * collections find their items with `querySelectorAll` on the content, so depth does
 * not matter. `asChild` on the content was the fallback and was not needed.
 * e2e/mouse.spec.ts:216-217 is what proves the header stayed outside the menu role.
 *
 * Six behaviours below are not Radix defaults, are load-bearing, and each has its own
 * comment: keys not reaching page.tsx's window listener, Tab landing where it would have
 * landed with no menu open, the trigger taking focus before an entry's action runs, an
 * entry highlighted the moment the menu opens, arrows walking the list synchronously, and
 * either arrow opening the menu from the trigger. Most are asserted in
 * e2e/14-menu-keyboard.spec.ts; read that file before changing any of them.
 *
 * An empty list renders nothing at all — not a disabled trigger, not an empty popover.
 * Defensive rather than observable: every current caller passes at least one action
 * nobody can be refused (`project.create`'s `when` is unconditional), so no live
 * permission combination reaches it today. A future caller's `when` list could.
 */
export function Menu({
  label,
  items,
  trigger = "⋯",
  header,
  footer,
  asChild = false,
}: {
  label: string;
  items: MenuItem[];
  trigger?: React.ReactNode;
  header?: React.ReactNode;
  footer?: React.ReactNode;
  /**
   * When set, `trigger` is not wrapped in a button of the menu's own — it *is* the
   * button, so it has to be one: Radix clones the child and hands it the trigger's
   * props, and a `<span>` would take `aria-haspopup` and an `onClick` without being
   * focusable or firing on Enter. The status and priority pills use this: what you are
   * already reading is what you click, which used to be faked with an invisible button
   * laid over the pill — 35 lines of `display: contents` and absolute positioning
   * deleted from globals.css in the same commit as this.
   */
  asChild?: boolean;
}) {
  // Open state is held here rather than left to Radix because two of the behaviours
  // below have to close the popover themselves, at a moment of their choosing.
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  /** Which end of the list the next opening should start from. */
  const openAt = useRef<"first" | "last">("first");
  /**
   * Set when this component has already put focus where it belongs, before the popover
   * closed. Radix's own restore then has to be called off: it runs on a timer, after
   * the close, and would land on top of ours — undoing Tab's move, or pulling focus out
   * of a dialog an entry has just opened.
   */
  const focusPlaced = useRef(false);

  /**
   * The focus follows the highlight from the moment the menu opens, at the end the menu
   * was opened from. Radix focuses the popover itself instead and leaves every entry
   * unhighlighted until an arrow is pressed; the hand-written menu highlighted an entry
   * immediately, and the keyboard is counted from there — two ArrowDowns reach the third
   * entry, not the second (e2e/14-menu-keyboard.spec.ts:59-62).
   *
   * A ref callback, which runs while the popover is being committed, rather than an
   * effect or a correction after the fact. Both of those were tried and both lose: the
   * focus scope inside the popover only learns its own container through a state update,
   * so its autofocus lands a commit later than this component's effects, and answering
   * its focus event afterwards puts the highlight one keystroke behind — a key pressed
   * in between reaches the popover, not an entry. Claiming the focus first turns the
   * race off instead of winning it: the scope finds the focus already inside its
   * container, and then leaves it alone entirely.
   *
   * Stable, so React attaches it once per opening. An inline callback would be detached
   * and re-attached on every render of the row, snapping the highlight back to the first
   * entry under someone walking the list.
   */
  const claimEntryFocus = useCallback((list: HTMLDivElement | null) => {
    if (!list) return;
    const entries = list.querySelectorAll<HTMLElement>('[role="menuitem"]');
    if (!entries.length) return;
    (openAt.current === "last" ? entries[entries.length - 1] : entries[0]).focus();
    openAt.current = "first";
  }, []);

  if (items.length === 0) return null;

  /** Close, having first put focus on a node that outlives the popover. */
  const closeOntoTrigger = () => {
    focusPlaced.current = true;
    triggerRef.current?.focus();
    setOpen(false);
  };

  return (
    // Not modal. Radix's default would block pointer events outside the popover, lock
    // scrolling and `aria-hidden` the rest of the document while a `⋯` is open — none
    // of which the hand-written menu did, and the first of them would swallow the click
    // that closes one menu on its way to whatever it was aimed at.
    <DropdownMenu open={open} onOpenChange={setOpen} modal={false}>
      <DropdownMenuTrigger
        ref={triggerRef}
        asChild={asChild}
        // `.menu` no longer wraps anything — Radix's root renders no element and the
        // popover is portalled — so it rides on the trigger, which is now the whole of
        // the menu that stands in the row. tickets.tsx:86 asks `closest(".menu")`
        // whether a double-click belongs to a menu rather than to the row underneath.
        className={asChild ? "menu" : "menu menu-trigger"}
        aria-label={label}
        // The row underneath changes the scope when it is clicked.
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
          // Both arrows open the menu, at the end they point at, and neither reaches
          // page.tsx's window handler. Radix opens on ArrowDown but lets the key
          // through, which would move the list cursor at the same time, and it ignores
          // ArrowUp entirely. `preventDefault` here also keeps Radix's own handler from
          // running (it composes ours ahead of its own and checks for exactly this),
          // so the menu is not opened twice over.
          event.preventDefault();
          event.stopPropagation();
          openAt.current = event.key === "ArrowDown" ? "first" : "last";
          setOpen(true);
        }}
      >
        {trigger}
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        // The arrows wrap: down from the last entry is the first one, up from the first
        // is the last, as the hand-written menu's modulo did — e2e/mouse.spec.ts:261-266
        // walks a four-entry menu in a full circle to prove nothing else is a stop along
        // the way. Set for the keys Radix still owns below (PageUp, PageDown), so that
        // its navigation and the four keys handled here agree about the ends.
        loop
        // No `.menu-popover`: that class positions a popover absolutely against
        // `.menu`, and this content is portalled to the body, where Radix computes and
        // applies its own position. Passing it would fight the library. What the class
        // also carried — surface, border, radius, shadow — the generated component
        // already applies with its own utilities.
        //
        // `role="presentation"` moves the menu role off the popover and onto the list
        // of entries below, which is where the docstring above argues it belongs.
        // Radix writes `role="menu"` before spreading these props, so this wins.
        role="presentation"
        // Two keys, and only two kinds of key, are caught above Radix rather than beside
        // it. The capture phase is what "above" means here: the popover is the ancestor
        // of the entry the key was pressed on, so stopping the event on the way down
        // keeps it from Radix's own handlers — the entry's and the popover's alike — and
        // from page.tsx's window listener at the same time. Everything else Radix does
        // with a key is left alone: typeahead, Enter and Space on an entry, Escape.
        onKeyDownCapture={(event) => {
          if (event.key === "Tab") {
            // Radix's popover handler calls `preventDefault()` on Tab, which would pin
            // focus inside a popover that is closing.
            event.stopPropagation();
            // Focus the trigger first, exactly as the hand-written menu did. A default
            // action resolves against `document.activeElement` at the time it *runs*,
            // not when the key was pressed; the entry holding focus is about to be
            // unmounted, and the browser would reset focus to `<body>` and then compute
            // "next focusable" from the top of the document instead of from this row.
            // The trigger stays mounted, so it is a live anchor for Tab and Shift+Tab
            // alike. No `preventDefault()`: the default action is what moves focus on.
            closeOntoTrigger();
            return;
          }

          // Walking the list is done here rather than by Radix's roving focus, which
          // moves focus inside a `setTimeout` — and Chrome runs input tasks ahead of
          // timer tasks, so a burst of arrows all read the same "current" entry and
          // arrive as a single move. The hand-written menu moved focus synchronously and
          // never lost a press; two ArrowDowns have to reach the third entry
          // (e2e/14-menu-keyboard.spec.ts:59-62), and they would reach the second.
          // This is the one place where Radix's navigation is taken over rather than
          // used, and it is four keys wide: the collection, the tab-stop bookkeeping and
          // the pointer behaviour are all still Radix's, because focusing an entry is
          // what its own `onFocus` reacts to.
          const entries = Array.from(
            event.currentTarget.querySelectorAll<HTMLElement>('[role="menuitem"]'),
          );
          if (!entries.length) return;
          const current = entries.indexOf(document.activeElement as HTMLElement);
          const next = {
            ArrowDown: current < 0 ? 0 : (current + 1) % entries.length,
            ArrowUp: current < 0 ? entries.length - 1 : (current - 1 + entries.length) % entries.length,
            Home: 0,
            End: entries.length - 1,
          }[event.key];
          if (next === undefined) return;
          event.preventDefault();
          event.stopPropagation();
          entries[next].focus();
        }}
        onKeyDown={(event) => {
          // page.tsx listens for keys on window. Radix does not shield it, so without
          // this every arrow inside the menu would also move the list cursor. Escape
          // still closes the popover: Radix listens for it on the document in the
          // capture phase, which has already run by the time this bubble handler does.
          event.stopPropagation();
        }}
        onCloseAutoFocus={(event) => {
          // Radix hands focus back to the trigger here, which is right for Escape and
          // for an outside click, and is what invariant 7 needs. It is wrong whenever
          // this component has already placed focus itself: it runs on a `setTimeout`
          // after the close, late enough to overwrite the browser's Tab or a dialog's
          // own autofocus. `preventDefault` also stops the composed Radix handler, so
          // one call turns off both.
          if (!focusPlaced.current) return;
          focusPlaced.current = false;
          event.preventDefault();
        }}
      >
        {header && <div className="menu-header">{header}</div>}
        <div className="menu-list" role="menu" aria-label={label} ref={claimEntryFocus}>
          {items.map((item) => (
            <DropdownMenuItem
              key={item.id}
              className="menu-item"
              data-danger={item.danger ? "true" : undefined}
              variant={item.danger ? "destructive" : "default"}
              // The portal moves the popover out of the row in the DOM, but React still
              // routes its events through the tree, so the row is upstream of this
              // click and would change the scope under the action about to run.
              onClick={(event) => event.stopPropagation()}
              onSelect={(event) => {
                // Radix would close the popover after this and put focus back on the
                // trigger a tick later. Too late for an entry that opens a dialog:
                // `DialogFrame` reads `document.activeElement` as it mounts and hands
                // focus back to it on unmount (dialogs/field.tsx:79-111), so it would
                // capture this entry — a node that is about to stop existing — and drop
                // focus to `<body>` when it closes. `preventDefault` takes the close
                // back from Radix so the order is the hand-written one: focus the
                // trigger, close, then run the action.
                event.preventDefault();
                closeOntoTrigger();
                item.onSelect();
              }}
            >
              <span className="menu-label">{item.label}</span>
              {item.hint && (
                <>
                  {/* A real space, not a CSS gap: it is what separates label from hint
                      in the accessible name and in `textContent`, so both read as
                      "Rename ticket e". The flex container drops the whitespace-only
                      box, so nothing is drawn for it. */}
                  {" "}
                  <span className="menu-hint">{item.hint}</span>
                </>
              )}
            </DropdownMenuItem>
          ))}
        </div>
        {footer && <div className="menu-footer">{footer}</div>}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
