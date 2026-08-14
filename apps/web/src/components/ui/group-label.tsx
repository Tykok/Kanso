import type { ComponentPropsWithoutRef } from "react";
import { cn } from "@/lib/utils";

/**
 * The small caption above a labelled section — the sidebar's "Teams" and
 * "Projects", and anywhere else a group of rows needs a heading rather than a rule.
 * 11px, uppercase, wide-tracked and the faintest ink: quiet enough that it reads as
 * structure, not as another row competing with the ones it introduces.
 *
 * `pt-group` is the space above it — the same token a run of rows uses to separate
 * itself from the group before it — so the gap before a heading and the gap before
 * the section it starts are the one number, not two that happen to agree today.
 */
export function GroupLabel({
  className,
  children,
  ...rest
}: {
  className?: string;
  children?: React.ReactNode;
} & Omit<ComponentPropsWithoutRef<"div">, "className" | "children">) {
  return (
    <div
      className={cn("pt-group px-row-x pb-2 text-11 font-medium tracking-[0.1em] text-faint uppercase", className)}
      {...rest}
    >
      {children}
    </div>
  );
}
