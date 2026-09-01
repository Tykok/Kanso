import type { ProjectHealth, ProjectStatus, TicketPriority, TicketStatus } from "./api";

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
 * What a status *means*, as opposed to what it is called. The API's `StatusCategory`,
 * spelled the same way and mapped the same way, because the two have to agree about what
 * "finished" is or the roadmap and the burndown drawn from the same tickets disagree.
 *
 * Nothing crosses the wire: the server sends statuses and this reads them. It exists so
 * that "is this finished", "has anybody started" and "is this still open" are asked once
 * here rather than re-derived from literals on every screen that needs an answer.
 */
export const STATUS_CATEGORIES = [
  "backlog",
  "unstarted",
  "started",
  "completed",
  "canceled",
] as const;

export type StatusCategory = (typeof STATUS_CATEGORIES)[number];

/**
 * `in_review` is `started` because somebody is holding it — a reviewer is work in flight,
 * and a chart that called review "not started" would draw the wrong day.
 */
export const STATUS_CATEGORY: Record<TicketStatus, StatusCategory> = {
  backlog: "backlog",
  todo: "unstarted",
  in_progress: "started",
  in_review: "started",
  done: "completed",
  canceled: "canceled",
};

export const categoryOf = (status: TicketStatus): StatusCategory => STATUS_CATEGORY[status];

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

/**
 * The third vocabulary on a project, and the one that is not a status.
 *
 * Kept in this module beside `PROJECT_STATUS_LABELS` so the two are read together and
 * never confused — but they are separate maps on purpose, and nothing here maps one onto
 * the other. A project can be `in_progress` and `off_track` at the same time.
 *
 * There is no entry for "not assessed": that is the absence of a health, so it has no key
 * to hold a label under. `healthLabel` in `views/project-copy.ts` is where the absent case
 * gets its words, and where the argument for not defaulting it to `on_track` is written.
 */
export const PROJECT_HEALTH_LABELS: Record<ProjectHealth, string> = {
  on_track: "On track",
  at_risk: "At risk",
  off_track: "Off track",
};

/**
 * Green, amber, red — reusing the three semantic tokens the app already has rather than
 * naming three more. `--success` and `--warning` back the `Badge` variants of the same
 * names, and `--urgent` is the red `tokens.css` keeps distinct from `--destructive`
 * ("an urgent ticket is not a failed one"), which is exactly the distinction wanted here:
 * a project that is off track is in trouble, not broken.
 *
 * The hue is never the only signal. Every place these are drawn also prints
 * `PROJECT_HEALTH_LABELS` beside them, for the reason `ui/status-dot.tsx` sets out at
 * length: a reader who cannot resolve the tint must lose nothing.
 */
export const PROJECT_HEALTH_COLORS: Record<ProjectHealth, string> = {
  on_track: "var(--success)",
  at_risk: "var(--warning)",
  off_track: "var(--urgent)",
};
