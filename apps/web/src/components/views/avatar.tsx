import { cn } from "@/lib/utils";
import { initialsOf } from "./initials";

/**
 * A person, drawn as the two letters of their name in a circle.
 *
 * The drawings size it three ways and never any other: 22px on a list row and in the
 * panel, 20px on the project page's lead, 18px on a board card and in a search result.
 * A prop with three values rather than a className, so a fourth size is a decision
 * somebody has to make here instead of one that arrives by accident at a call site.
 *
 * `title` and not `aria-label`: the circle sits beside or inside something that already
 * names the person for a screen reader — the chip's own text on 03, the card's
 * accessible name on 04 — so a second announcement would be a repetition, while a
 * pointer user hovering an 18px circle genuinely has nothing else to read.
 */
export function Avatar({
  displayName,
  size = 22,
}: {
  displayName?: string | null;
  size?: 18 | 20 | 22;
}) {
  return (
    <span
      aria-hidden
      title={displayName ?? "Unassigned"}
      className={cn(
        "inline-flex shrink-0 items-center justify-center rounded-full bg-accent text-muted-foreground",
        size === 18 && "size-[18px] text-[9px]",
        size === 20 && "size-5 text-[9px]",
        size === 22 && "size-[22px] text-[10px]",
      )}
    >
      {initialsOf(displayName)}
    </span>
  );
}
