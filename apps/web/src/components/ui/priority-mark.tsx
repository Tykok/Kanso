import type { TicketPriority } from "@/lib/api";
import { PRIORITY_COLORS, PRIORITY_GLYPHS } from "@/lib/status";

/**
 * A glyph in its own colour, never a background fill: priority is a second
 * axis and must not compete with the status it sits beside.
 *
 * Same threshold reasoning as `StatusDot`, and for the same structural
 * reason: `aria-hidden`, always paired with a text label elsewhere (the
 * pill's `title`, or the row's own text), so WCAG 1.4.11's 3:1 non-text
 * floor is what applies, not 1.4.3's 4.5:1 text floor. The glyph itself
 * (`!`, `█`, `▄`, `▁`, `·`) carries the meaning as much as the hue does —
 * `--priority-low`/`--priority-none` deliberately reuse the same receding
 * neutral as backlog/canceled and clear only 2.78:1/2.15:1 against
 * `--background` in light (3:1+ in dark, like every other priority hue in
 * both schemes). Do not raise them to hit the text floor; that plane is
 * shared with the status hues on purpose.
 */
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
