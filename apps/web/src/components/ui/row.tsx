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
 * hovered row from reading as a selected one.
 *
 * Layout is the caller's: this sets no `display` and no columns, so a five-column
 * dependency chip and an eight-column ticket row both reach for it via `className`
 * instead of each writing their own `grid`. No ARIA role either — neither list wraps
 * its rows in the `grid`/`table` ancestor a `row` role would need to mean anything,
 * so it would be decoration, not semantics. Forwards its ref: the ticket list keeps
 * the selected row scrolled into view by calling `scrollIntoView` on the node
 * directly, and a caller behind a ref is a caller this component cannot see.
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
        "h-row shrink-0 items-center gap-3 rounded-md px-row-x cursor-pointer hover:bg-accent",
        selected && "bg-accent-soft text-foreground shadow-[inset_2px_0_0_var(--primary)]",
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  );
});
