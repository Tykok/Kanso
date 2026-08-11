import type { TicketStatus } from "./api";

/**
 * How a status is written and coloured, wherever it is drawn.
 *
 * A module rather than an export from `pills.tsx`: the chart would otherwise import
 * from a component of the list view, which is a dependency in the wrong direction for
 * two screens that are siblings.
 */
export const STATUS_LABELS: Record<TicketStatus, string> = {
  backlog: "Backlog",
  todo: "Todo",
  in_progress: "In progress",
  in_review: "In review",
  done: "Done",
  canceled: "Canceled",
};

export const STATUS_COLORS: Record<TicketStatus, string> = {
  backlog: "var(--status-backlog)",
  todo: "var(--status-todo)",
  in_progress: "var(--status-progress)",
  in_review: "var(--status-review)",
  done: "var(--status-done)",
  canceled: "var(--status-canceled)",
};
