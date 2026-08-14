"use client";

import { useCallback, useId, useRef, useState } from "react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Kbd } from "@/components/ui/kbd";
import { cn } from "@/lib/utils";

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
 * The behaviours below are not Radix defaults, are load-bearing, and each carries its own
 * comment. Deliberately not numbered: an earlier version of this paragraph claimed seven
 * and listed six, which is what a count in a comment does over time.
 *
 *   - no key reaching page.tsx's window listener, from the popover
 *   - the same from the trigger, where Enter would otherwise open this menu *and* a ticket
 *   - Tab landing where it would have landed with no menu open
 *   - the trigger taking focus before an entry's action runs
 *   - an entry highlighted the moment the menu opens, however the menu was opened
 *   - arrows walking the list synchronously, because Radix's move is deferred
 *   - either arrow opening the menu from the trigger
 *   - a double-click inside the popover not reaching the row underneath
 *   - aria-controls pointing at the entries, not at the popover this file demotes
 *
 * Most are asserted in e2e/14-menu-keyboard.spec.ts; read that file before changing any.
 *
 * Four of them rest on Radix behaviour its docs do not promise: that composeEventHandlers
 * stands down on defaultPrevented, that `role` is written before contentProps are spread,
 * that `aria-controls` is written before triggerProps are spread, and that the item
 * collections use querySelectorAll on the content so the nested role="menu" costs nothing.
 * `radix-ui` is on a caret range. Treat a minor bump as a change to this file: re-run
 * scenario 14 and mouse.spec.ts scenarios 10 and 11 before letting it land.
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
  /**
   * Names the element that actually holds the menu role, for the trigger to point at.
   * Radix's own `aria-controls` names the popover, which `role="presentation"` below has
   * just demoted to nothing — leaving `aria-haspopup="menu"` announcing a popup the
   * trigger has no stated relationship to, which is a step down from the hand-written
   * file in the very area invariant 6 exists to protect.
   */
  const menuId = useId();
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
    // A flat query of every entry, for the reason given at the capture handler below.
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
        // the menu that stands in the row. tickets.tsx:114 asks `closest(".menu")`
        // whether a double-click belongs to a menu rather than to the row underneath.
        className={cn(
          "menu shrink-0",
          !asChild &&
            "flex size-5 items-center justify-center rounded-sm leading-none text-faint hover:bg-accent hover:text-foreground aria-expanded:bg-accent aria-expanded:text-foreground",
        )}
        data-testid={asChild ? undefined : "menu-trigger"}
        aria-label={label}
        // Radix writes its own `aria-controls` — naming the popover — before spreading
        // these props, so this replaces it with the id of the `role="menu"` element the
        // entries actually live in. See `menuId` above.
        aria-controls={open ? menuId : undefined}
        // The row underneath changes the scope when it is clicked.
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === " ") {
            // Radix opens the menu from its own keydown handler and calls
            // `preventDefault()` there, but never `stopPropagation()` — so the key would
            // go on to page.tsx's window listener, where Enter is `ticket.open`, and one
            // press would open this menu *and* the selected ticket's panel behind it.
            // The hand-written trigger was a plain button that opened on the activation
            // click, which page.tsx's own `preventDefault()` cancelled; exactly one thing
            // happened, and stopping the event here is what restores that. Stopping it
            // *without* preventing the default is the whole point: Radix composes this
            // handler ahead of its own and only stands down when the default was
            // prevented, so its toggle still runs.
            event.stopPropagation();
            return;
          }
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
        // No `loop`: it would be dead configuration. Radix consults it only for the
        // `prev`/`next` intents, which are exactly the two arrows the capture handler
        // below takes over — PageUp and PageDown map to `first`/`last` and never look at
        // it. The wrap those arrows need is the modulo down there instead.
        //
        // No `.menu-popover`: that class positions a popover absolutely against
        // `.menu`, and this content is portalled to the body, where Radix computes and
        // applies its own position. Passing it would fight the library. What the class
        // also carried — surface, border, radius, shadow — the generated component
        // already applies with its own utilities. All but the border's colour: `border`
        // emits width and style only, so it relies on the default in `tokens.css`, and
        // without that default this popover drew a near-black line around itself.
        //
        // `role="presentation"` moves the menu role off the popover and onto the list
        // of entries below, which is where the docstring above argues it belongs.
        // Radix writes `role="menu"` before spreading these props, so this wins.
        role="presentation"
        // Five keys — Tab, and the four that move the highlight — are caught above Radix
        // rather than beside it. The capture phase is what "above" means here: the popover
        // is the ancestor
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
          // what its own `onFocus` reacts to. The wrap at either end is the modulo
          // below — e2e/mouse.spec.ts:261-266 walks a four-entry menu in a full circle.
          //
          // A flat query, where Radix's own collection also skips disabled items and
          // stops at its own content: `MenuItem` here is never disabled and never opens
          // a submenu, so there is nothing to skip and nothing deeper to descend into.
          // Should either become possible, this query and the one in `claimEntryFocus`
          // both have to learn it.
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
        // A double-click on the popover — its 4px of padding is the reachable part — is
        // routed by React up to the row that owns this menu, and `dblclick` is its own
        // native event, so the entries' click stop never touches it. tickets.tsx:114
        // guards the row with `closest(".menu")`, which used to match anywhere inside a
        // popover that was a child of the `.menu` wrapper; portalled, it no longer does,
        // and that call site is not this task's to edit. Stopping it here is the same
        // guard from the other side.
        onDoubleClick={(event) => event.stopPropagation()}
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
        {header && (
          <div
            data-testid="menu-header"
            className="mb-1 flex flex-col gap-0.5 border-b border-border px-2 py-1.5 text-11 text-faint [&>strong]:text-12 [&>strong]:font-medium [&>strong]:text-foreground"
          >
            {header}
          </div>
        )}
        <div
          id={menuId}
          className="flex flex-col gap-px"
          role="menu"
          aria-label={label}
          ref={claimEntryFocus}
        >
          {items.map((item) => (
            <DropdownMenuItem
              key={item.id}
              className={cn(
                "flex items-center gap-4 rounded-sm px-2 py-1.5 text-12 whitespace-nowrap text-muted-foreground hover:bg-accent hover:text-foreground focus:bg-accent focus:text-foreground",
                // The destructive entry is set apart with a rule of its own, not with an
                // `<hr>` between two `role="menuitem"` elements — that would break the
                // relationship a menu is expected to have with its entries.
                item.danger &&
                  "mt-1 border-t border-border pt-2 text-urgent hover:text-urgent focus:text-urgent",
              )}
              data-danger={item.danger ? "true" : undefined}
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
              <span>{item.label}</span>
              {item.hint && (
                <>
                  {/* A real space, not a CSS gap: it is what separates label from hint
                      in the accessible name and in `textContent`, so both read as
                      "Rename ticket e". The flex container drops the whitespace-only
                      box, so nothing is drawn for it. */}
                  {" "}
                  <Kbd className="ml-auto shrink-0">{item.hint}</Kbd>
                </>
              )}
            </DropdownMenuItem>
          ))}
        </div>
        {footer && (
          <div
            data-testid="menu-footer"
            className="mt-1 flex flex-col gap-0.5 border-t border-border px-2 py-1.5 font-mono text-11 text-faint"
          >
            {footer}
          </div>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
