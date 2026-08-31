import type { ImportTarget } from "./import-map";

/**
 * How the five answers are named and drawn, in one place.
 *
 * Two of the three steps show them — the second to choose, the third to restate what was
 * chosen — and a label that disagreed between them would be a mapping the reader confirms
 * against the wrong word.
 */
export const TARGET_LABELS: Record<ImportTarget, string> = {
  teams: "Teams",
  projects: "Projects",
  tickets: "Tickets",
  documents: "Documents",
  ignore: "Ignore",
};

/** The dot beside a target, from the closed status palette rather than a new colour. */
export const TARGET_DOTS: Record<ImportTarget, string> = {
  teams: "bg-status-backlog",
  projects: "bg-status-review",
  tickets: "bg-status-progress",
  documents: "bg-status-done",
  ignore: "bg-transparent",
};

/**
 * How each field of a target is named on the columns step — the fifteen `ImportField`
 * wire strings, in the reader's words rather than the wire's.
 *
 * Here beside [TARGET_LABELS] for the same reason: two steps print them, the third's
 * warning quotes them, and a label that disagreed between them would be a mapping the
 * reader confirms against the wrong word.
 */
export const FIELD_LABELS: Record<string, string> = {
  status: "Status",
  priority: "Priority",
  description: "Description",
  start: "Start",
  due: "Due",
  end: "End",
  assignees: "Assignees",
  lead: "Lead",
  project: "Project",
  team: "Team",
  parentTeam: "Parent team",
  blockedBy: "Blocked by",
  tickets: "Tickets",
  projects: "Projects",
  subTeams: "Sub-teams",
};

/** The fields that name people, and so the ones that put step 4 on the way to step 5. */
export const PEOPLE_FIELDS = ["assignees", "lead"] as const;

/** The drawing's own three columns: the base, how much of it, what it becomes. */
export const ROW_GRID = "grid-cols-[1fr_130px_150px]";

/**
 * "248 pages", or "2000+ pages" when the server stopped counting at its bound.
 *
 * The `+` is the whole reason `pagesExact` is on the wire: Notion answers no total for a
 * data source, so a count is a walk, and a bounded walk that printed a bare number would
 * put a wrong one in front of a confirm button.
 */
export const pageCount = (pages: number, exact: boolean) => `${pages}${exact ? "" : "+"} pages`;
