import type { TicketPriority } from "@/lib/api";
import { PRIORITY_COLORS, PRIORITY_GLYPHS } from "@/lib/status";

/**
 * A glyph in its own colour, never a background fill: priority is a second
 * axis and must not compete with the status it sits beside.
 *
 * If you arrived here with a contrast checker: `low` and `none` measure
 * 2.78:1 and 2.15:1 against `--background` in light mode (both clear 3:1
 * in dark, like every other priority hue in both schemes — this is the
 * one gap, not a pattern). That fails WCAG 1.4.3's 4.5:1 text floor, but
 * 1.4.3 isn't the criterion that governs this glyph. WCAG 1.4.11
 * (Non-text Contrast) is, and it exempts elements that are decorative and
 * whose information is available in text elsewhere: this glyph is
 * `aria-hidden`, and it always sits beside a text label (the pill's
 * `title`, or the row's own text) that already says the priority in
 * words. What is left for a reader who cannot resolve the tint — `!`,
 * `█`, `▄`, `▁`, `·`, five different shapes — still carries the meaning
 * on its own.
 *
 * The paleness is deliberate, not a gap to close. `--priority-low` and
 * `--priority-none` reuse the exact same receding neutral as
 * `backlog`/`canceled`, because "low" and "no priority" are the *absent*
 * end of this axis the same way those two are the absent end of status —
 * nothing has been asked for. Darkening them to hit a number would make
 * an unprioritised ticket read as loud as an urgent one, which is what
 * this axis exists to prevent. Do not raise them to hit the text floor;
 * that plane is shared with the status hues on purpose.
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
