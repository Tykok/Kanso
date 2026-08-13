import type { TicketPriority } from "@/lib/api";
import { PRIORITY_COLORS, PRIORITY_GLYPHS } from "@/lib/status";

/** A glyph in its own colour, never a background fill: priority is a second
 *  axis and must not compete with the status it sits beside. */
export function PriorityMark({ priority }: { priority: TicketPriority }) {
  return (
    <span
      aria-hidden
      className="w-3.5 shrink-0 text-center text-11 font-medium"
      style={{ color: PRIORITY_COLORS[priority] }}
    >
      {PRIORITY_GLYPHS[priority]}
    </span>
  );
}
