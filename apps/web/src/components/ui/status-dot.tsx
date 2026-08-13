import type { TicketStatus } from "@/lib/api";
import { STATUS_COLORS } from "@/lib/status";

/** backlog and todo are rings, in progress is half, in review is three
 *  quarters, done and canceled are full. The fill is the meaning; the hue
 *  only makes it faster to find. */
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
