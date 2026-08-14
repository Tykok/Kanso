import type { TicketStatus } from "@/lib/api";
import { STATUS_COLORS } from "@/lib/status";

/**
 * backlog and todo are rings, in progress is half, in review is three
 * quarters, done and canceled are full. The fill is the meaning; the hue
 * only makes it faster to find.
 *
 * That is also why this dot answers to WCAG 1.4.11's 3:1 non-text floor
 * rather than 1.4.3's 4.5:1 text floor: it is `aria-hidden` and always
 * drawn beside `StatusPill`'s own text label, which carries the accessible
 * content. Backlog and canceled sit close to `--background` on purpose —
 * both are meant to recede — and clear only 2.78:1/2.15:1 in light (they
 * clear 3:1 in dark, along with every other status hue in both schemes;
 * measured against `--background` directly, not assumed). Raising either
 * to 4.5:1 would flatten the one-lightness-plane the six status hues share
 * on purpose (see `tokens.css`) to satisfy a floor that governs text, not
 * a decorative dot whose shape — not its colour — is what has to survive
 * colour blindness and a greyscale screenshot.
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
