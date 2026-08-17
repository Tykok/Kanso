import { PRIORITY_LABELS, STATUS_LABELS } from "@/lib/status";
import type { TicketStatus, ViewFilters } from "@/lib/api";

/**
 * A saved view's filters, as the removable chips screen 21 draws across the top.
 *
 * One chip per facet rather than one per value: `Priority Urgent, High` is one question
 * with two answers, and splitting it into two chips would make the `×` on either of them
 * ambiguous about whether it removes the value or the question.
 */
export type Chip = {
  /** The facet, which is what the `×` removes. */
  key: keyof ViewFilters;
  label: string;
  /** Empty for a facet that is its own answer, like `Unassigned`. */
  value: string;
};

/** How each key reads on screen. The drawing's own words, in English. */
const LABELS: Record<keyof ViewFilters, string> = {
  status: "Status",
  statusNot: "Status",
  priority: "Priority",
  project: "Project",
  assignee: "Assignee",
  unassigned: "Unassigned",
  cycle: "Cycle",
  label: "Label",
  openedForDays: "Open for",
};

/** Left to right as the drawing has them, so the strip does not reshuffle on every edit. */
const ORDER: (keyof ViewFilters)[] = [
  "project",
  "status",
  "statusNot",
  "priority",
  "assignee",
  "unassigned",
  "cycle",
  "label",
  "openedForDays",
];

/** Resolves the ids a filter stores into the names a reader recognises. */
export type ChipNames = {
  project: (id: string) => string;
  person: (id: string) => string;
  cycle: (id: string) => string;
  label: (id: string) => string;
};

const titleCase = (wire: string) =>
  wire.replace(/_/g, " ").replace(/^./, (first) => first.toUpperCase());

export function chipsOf(filters: ViewFilters, names: ChipNames): Chip[] {
  return ORDER.flatMap((key) => {
    const value = valueOf(key, filters, names);
    return value === undefined ? [] : [{ key, label: LABELS[key], value }];
  });
}

/**
 * The `×`. Deletes the key rather than emptying it: the server validates the keys it is
 * sent, and `{ project: [] }` would be a chip the screen no longer draws still riding along
 * on every write of the view.
 */
export function withoutChip(filters: ViewFilters, key: keyof ViewFilters): ViewFilters {
  const next = { ...filters };
  delete next[key];
  return next;
}

function valueOf(
  key: keyof ViewFilters,
  filters: ViewFilters,
  names: ChipNames,
): string | undefined {
  switch (key) {
    case "unassigned":
      // A boolean facet is its own answer, so the chip is the label alone. `Unassigned true`
      // would read as a database row rather than as a question somebody asked.
      return filters.unassigned ? "" : undefined;
    case "openedForDays":
      return filters.openedForDays === undefined
        ? undefined
        : `more than ${filters.openedForDays} days`;
    case "status":
      return joined(filters.status?.map(statusLabel));
    case "statusNot": {
      // The drawing writes this chip `Statut ≠ Done`, and the sign is the whole point: a
      // chip reading `Status Done` over a list of everything that is not done is backwards.
      const listed = joined(filters.statusNot?.map(statusLabel));
      return listed === undefined ? undefined : `≠ ${listed}`;
    }
    case "priority":
      return joined(filters.priority?.map((priority) => PRIORITY_LABELS[priority]));
    case "project":
      return joined(filters.project?.map(names.project));
    case "assignee":
      return joined(filters.assignee?.map(names.person));
    case "cycle":
      return joined(filters.cycle?.map(names.cycle));
    case "label":
      return joined(filters.label?.map(names.label));
  }
}

const statusLabel = (status: TicketStatus) => STATUS_LABELS[status] ?? titleCase(status);

const joined = (values: string[] | undefined) =>
  values === undefined || values.length === 0 ? undefined : values.join(", ");
