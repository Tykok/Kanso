"use client";

import { useEffect, useId, useRef, useState } from "react";

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
 * The dropdown every mouse path in the application goes through: a row's `⋯`, the
 * sidebar's team and project rows, the top bar's `New`, the brand block's account
 * menu, and a ticket's status and priority pills — the last two passing `trigger={null}`
 * so the pill itself is the button.
 *
 * Nothing like it existed before — no dropdown, no context menu — so everything is
 * written here: the roving focus pattern (`tabindex` 0 on the current entry, −1 on the
 * others), closing on an outside click, and stopping key propagation so the `window`
 * handler in `page.tsx` does not move the list cursor while someone is walking the menu.
 *
 * `role="menu"` sits on the list of entries, not on the popover. A `menu` may only own
 * `menuitem`, `menuitemradio`, `menuitemcheckbox`, `group` and `separator`; the header
 * and the footer are neither, and assistive technology is free to drop whatever else it
 * finds inside one. What would be dropped is the identity block — the one thing the
 * spec says answers a question nothing else in the interface answers — so they are
 * siblings of the list instead. `aria-hidden` would have guaranteed the loss it was
 * meant to license.
 *
 * An empty list renders nothing: that is what makes a member see no `⋯` at all on a
 * team row, without the caller having to know about it.
 */
export function Menu({
  label,
  items,
  trigger = "⋯",
  header,
  footer,
}: {
  label: string;
  items: MenuItem[];
  trigger?: React.ReactNode;
  header?: React.ReactNode;
  footer?: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const menuId = useId();

  /**
   * In the capture phase: a click on another row's `⋯` closes this one before that
   * one opens, rather than leaving two menus open.
   */
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => document.removeEventListener("pointerdown", onPointerDown, true);
  }, [open]);

  // The focus follows the highlight: it is what brings Escape and the arrows here.
  useEffect(() => {
    if (open) itemRefs.current[active]?.focus();
  }, [open, active]);

  if (items.length === 0) return null;

  const dismiss = () => {
    setOpen(false);
    triggerRef.current?.focus();
  };

  return (
    <div className="menu" ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className="menu-trigger"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={(event) => {
          // The row underneath changes the scope when it is clicked.
          event.stopPropagation();
          setActive(0);
          setOpen((current) => !current);
        }}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            event.stopPropagation();
            setActive(event.key === "ArrowDown" ? 0 : items.length - 1);
            setOpen(true);
          }
        }}
      >
        {trigger}
      </button>

      {open && (
        <div
          className="menu-popover"
          onKeyDown={(event) => {
            // `page.tsx` listens on window: without this stop, every arrow would
            // also move the cursor in the ticket list.
            event.stopPropagation();
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setActive((index) => (index + 1) % items.length);
            } else if (event.key === "ArrowUp") {
              event.preventDefault();
              setActive((index) => (index - 1 + items.length) % items.length);
            } else if (event.key === "Home") {
              event.preventDefault();
              setActive(0);
            } else if (event.key === "End") {
              event.preventDefault();
              setActive(items.length - 1);
            } else if (event.key === "Escape") {
              event.preventDefault();
              dismiss();
            } else if (event.key === "Tab") {
              // Both directions need the same fix. `setOpen(false)` is a discrete
              // update: React flushes it synchronously, before this handler
              // returns, which unmounts the popover — including whichever item
              // currently holds focus — before the browser gets to resolve Tab's
              // native "move focus" default action. A default action resolves
              // against `document.activeElement` at the time it *runs*, not at
              // the time the key was pressed; if that node has already been
              // removed, the browser resets focus to `<body>` first, so the
              // default action then computes "next/previous focusable" from the
              // top of the document instead of from this row. Refocusing the
              // trigger first — a node that stays mounted regardless of `open` —
              // gives the browser a live, correct anchor to resolve *either*
              // Tab's or Shift+Tab's default action against, so focus still ends
              // up on whatever follows or precedes the row, as intended.
              triggerRef.current?.focus();
              setOpen(false);
              // No preventDefault(): the browser's own default action, run
              // against the now-focused trigger, is what moves focus onward.
            }
            // Enter and Space are not intercepted: the entries are `<button>`s, and
            // the browser already fires them.
          }}
        >
          {header && <div className="menu-header">{header}</div>}
          <div id={menuId} className="menu-list" role="menu" aria-label={label}>
            {items.map((item, index) => (
              <button
                key={item.id}
                ref={(node) => {
                  itemRefs.current[index] = node;
                }}
                type="button"
                role="menuitem"
                className="menu-item"
                data-danger={item.danger ? "true" : undefined}
                tabIndex={index === active ? 0 : -1}
                onMouseEnter={() => setActive(index)}
                onClick={(event) => {
                  event.stopPropagation();
                  // Before the popover unmounts, and before the action runs: an entry
                  // that opens a dialog is about to become the thing that dialog
                  // restores focus to when it closes, and this button will not be
                  // there any more. The trigger will. Same reasoning as Tab above.
                  triggerRef.current?.focus();
                  setOpen(false);
                  item.onSelect();
                }}
              >
                <span className="menu-label">{item.label}</span>
                {item.hint && (
                  <>
                    {/* A real space, not a CSS gap: it is what separates label from
                        hint in the accessible name and in `textContent`, so both read
                        as "Rename ticket e". The flex container drops the
                        whitespace-only box, so nothing is drawn for it. */}
                    {" "}
                    <span className="menu-hint">{item.hint}</span>
                  </>
                )}
              </button>
            ))}
          </div>
          {footer && <div className="menu-footer">{footer}</div>}
        </div>
      )}
    </div>
  );
}
