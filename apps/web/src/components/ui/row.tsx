import { forwardRef, type ComponentPropsWithoutRef } from "react";
import { cn } from "@/lib/utils";

/**
 * The shared chrome for one line in a list of records: the ticket list's own rows,
 * and (from the ticket panel) the rows of a dependency list — two screens that need
 * the same height, padding and states and must not each draw them.
 *
 * `h-row`, `px-row-x` and `rounded-md` resolve through the density tokens, so one
 * class answers both densities with no variant to choose between. There is no
 * border: the gap between rows *is* the separation, and adding a rule here is how a
 * row stops matching the design.
 *
 * Selected is a tint plus a left accent bar; hover is the shared accent fill —
 * `--accent` is shadcn's hover colour, not the brand one, which is what keeps a
 * hovered row from reading as a selected one. The two are written as an either/or,
 * never both present on the same element: `hover:bg-accent` at (0,2,0) would beat
 * `bg-accent-soft` at (0,1,0) whenever a selected row is also hovered, which is the
 * bug the previous, CSS-file version of this row did not have (`.row:hover` and
 * `.row[data-selected="true"]` tied at equal specificity, and selected, declared
 * second, won).
 *
 * `group` is this row's own — a descendant that wants to hide until the row is
 * hovered or holds focus reaches for `group-hover:`/`group-focus-within:` rather
 * than this file (or a stylesheet) reaching into that descendant's own classes,
 * which is what the deleted `list.css` did to `menu.tsx`'s `.menu-trigger` and is
 * exactly the coupling this component exists to avoid repeating.
 *
 * Layout is the caller's: this sets no `display`, only `items-center` and `gap-3` —
 * inert without a `flex` or `grid` the caller supplies, and matching every row this
 * component draws today (the ticket list's 8 columns, a dependency chip's 5), so
 * they ride along rather than being repeated at every call site. No ARIA role
 * either — neither list wraps its rows in the `grid`/`table` ancestor a `row` role
 * would need to mean anything, so it would be decoration, not semantics. Forwards
 * its ref: the ticket list keeps the selected row scrolled into view by calling
 * `scrollIntoView` on the node directly, and a caller behind a ref is a caller this
 * component cannot see.
 */
export const Row = forwardRef<
  HTMLDivElement,
  {
    selected?: boolean;
    className?: string;
    children?: React.ReactNode;
  } & Omit<ComponentPropsWithoutRef<"div">, "className" | "children">
>(function Row({ selected, className, children, ...rest }, ref) {
  return (
    <div
      ref={ref}
      data-selected={selected ?? false}
      className={cn(
        "group h-row shrink-0 items-center gap-3 rounded-md px-row-x cursor-pointer",
        selected
          ? "bg-accent-soft text-foreground shadow-[inset_2px_0_0_var(--primary)]"
          : "hover:bg-accent",
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  );
});

/**
 * A row's own `⋯`: out of sight at rest, revealed the moment the row it lives in
 * is worth acting on — hovered, holding focus anywhere inside it, or already open.
 * The wiki's `Spec - Every action reachable with a mouse` lists this as shipped,
 * deliberate behaviour, not a stylistic default the drawings happened to permit.
 *
 * A class string, not a wrapper: `opacity` is not an inherited CSS property, so a
 * `<span opacity-0>` around the trigger would look right and still read back as
 * `1` on the trigger's *own* computed style — which is exactly what a test
 * checking "is this button visible" has to read. Applying these classes straight
 * to a caller's own trigger button (passed to `<Menu asChild trigger={…} />`, the
 * same pattern `pills.tsx`, `brand-menu.tsx` and `new-menu.tsx` already use) means
 * the property lives on the element being asked about.
 *
 * `group-hover:`/`group-focus-within:` read the nearest ancestor's own `group`
 * class (`<Row>`'s, or a sidebar row's); `aria-expanded:` reads the ARIA state
 * Radix already sets on the trigger it is applied to — a public contract, not a
 * class name `menu.tsx` owns. Nothing here names `.menu-trigger` or any other
 * class belonging to another component, which is the difference between this and
 * the `list.css` rule it replaces: that one reached into another component's
 * class from a stylesheet; this names only ARIA state and its own element.
 */
export const rowActionsTriggerClass =
  "flex size-5 shrink-0 items-center justify-center rounded-sm text-faint opacity-0 " +
  "group-hover:opacity-100 group-focus-within:opacity-100 aria-expanded:opacity-100 " +
  "hover:bg-accent hover:text-foreground aria-expanded:bg-accent aria-expanded:text-foreground";
