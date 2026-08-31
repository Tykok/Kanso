import type { ProjectStatus, TicketPriority, TicketStatus } from "./api";

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

/**
 * The glyphs a priority already reads as at the keyboard — unchanged, so nothing
 * has to be relearned. Only their colours move to the token layer below.
 */
export const PRIORITY_GLYPHS: Record<TicketPriority, string> = {
  urgent: "!",
  high: "█",
  medium: "▄",
  low: "▁",
  none: "·",
};

export const PRIORITY_COLORS: Record<TicketPriority, string> = {
  urgent: "var(--urgent)",
  high: "var(--priority-high)",
  medium: "var(--priority-medium)",
  low: "var(--priority-low)",
  none: "var(--priority-none)",
};

/** Was copied verbatim into `pills.tsx` and `composer.tsx`; both now read this one. */
export const PRIORITY_LABELS: Record<TicketPriority, string> = {
  none: "No priority",
  low: "Low",
  medium: "Medium",
  high: "High",
  urgent: "Urgent",
};

/** The other status vocabulary, whose words a project's own screens print. */
export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  planned: "Planned",
  in_progress: "In progress",
  paused: "Paused",
  completed: "Completed",
  canceled: "Canceled",
};
