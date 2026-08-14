import type { TicketStatus } from "@/lib/api";
import { STATUS_COLORS } from "@/lib/status";

/**
 * backlog and todo are rings, in progress is half, in review is three
 * quarters, done and canceled are full. The fill is the meaning; the hue
 * only makes it faster to find.
 *
 * If you arrived here with a contrast checker: `backlog` and `canceled`
 * measure 2.78:1 and 2.15:1 against `--background` in light mode (both
 * clear 3:1 in dark, like every other status hue in both schemes — this is
 * the one gap, not a pattern). Under WCAG 1.4.3's 4.5:1 text floor that
 * reads as a failure. It isn't one, because 1.4.3 doesn't apply here at
 * all: WCAG 1.4.11 (Non-text Contrast) is the relevant success criterion,
 * and it exempts elements that are (a) pure decoration and (b) whose
 * information is available in text elsewhere — both true of this dot. It
 * is `aria-hidden`, so a screen reader never meets its colour; it is
 * always rendered beside `StatusPill`'s own text label, which carries the
 * accessible content; and what a sighted reader who cannot resolve the
 * tint is left with — the ring, the half, the three-quarters, the full
 * fill — still says everything the hue does. Nobody loses the status.
 *
 * The paleness is also not an oversight to fix. `backlog` and `canceled`
 * are the two *absent* states — nothing has started, or it was abandoned
 * — and they are supposed to recede next to the four active ones.
 * Darkening `backlog` to hit a number would pull it toward `todo`, which
 * this system deliberately keeps apart; darkening `canceled` would make a
 * dead ticket read as more present than a live one, the exact inverse of
 * what the colour is for. Raising either to 4.5:1 breaks the plane these
 * six hues share on purpose (see `tokens.css`) to satisfy a floor that was
 * never theirs to clear.
 */
const FILL: Record<TicketStatus, string | undefined> = {
  backlog: undefined,
  todo: undefined,
  in_progress: "linear-gradient(90deg, currentColor 50%, transparent 50%)",
  in_review: "conic-gradient(currentColor 0 75%, transparent 75% 100%)",
  done: "currentColor",
  canceled: "currentColor",
};

export function StatusDot({ status }: { status: TicketStatus }) {
  return (
    <span
      aria-hidden
      className="size-2 shrink-0 rounded-full border-[1.5px] border-current"
      style={{ color: STATUS_COLORS[status], background: FILL[status] }}
    />
  );
}
