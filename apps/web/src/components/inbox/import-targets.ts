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
