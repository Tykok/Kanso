import { PRIORITY_LABELS, STATUS_LABELS } from "@/lib/status";
import type {
  Ticket,
  TicketPriority,
  TicketStatus,
  ViewGroupBy,
  WorkloadRow,
} from "@/lib/api";

/**
 * How the rows are stacked on screens 19 and 21, and the sentence under screen 23.
 *
 * The server sorts and the client groups. A grouped wire shape would be a second
 * representation of one list that could disagree with itself, so the one rule this module
 * has to keep is that grouping never reorders **inside** a group — otherwise the view's
 * `sortBy` silently stops working for every grouped view.
 */
export type Group = {
  /** The value rows were grouped on. Empty string for "these have none". */
  key: string;
  /** Empty when the answer was not to group at all. */
  label: string;
  count: number;
  tickets: Ticket[];
};

/** Downwards as the work flows: what is waiting at the top, what is finished at the bottom. */
const STATUS_ORDER: TicketStatus[] = [
  "backlog",
  "todo",
  "in_progress",
  "in_review",
  "done",
  "canceled",
];

const PRIORITY_ORDER: TicketPriority[] = ["urgent", "high", "medium", "low", "none"];

export function groupTickets(
  tickets: readonly Ticket[],
  groupBy: ViewGroupBy,
  names?: { person?: (id: string) => string; project?: (id: string) => string },
): Group[] {
  if (tickets.length === 0) return [];
  // `none` is a real choice on the group-by control and it means one flat list — not zero
  // groups, which would render as an empty screen.
  if (groupBy === "none") {
    return [{ key: "", label: "", count: tickets.length, tickets: [...tickets] }];
  }

  const buckets = new Map<string, Ticket[]>();
  for (const ticket of tickets) {
    const key = keyOf(ticket, groupBy);
    const bucket = buckets.get(key);
    if (bucket) bucket.push(ticket);
    else buckets.set(key, [ticket]);
  }

  return [...buckets.entries()]
    .sort(([left], [right]) => rank(left, groupBy) - rank(right, groupBy))
    .map(([key, rows]) => ({
      key,
      label: labelOf(key, groupBy, names),
      count: rows.length,
      tickets: rows,
    }));
}

/**
 * The sentence under screen 23's chart, or nothing.
 *
 * Nothing rather than a reassurance: the strip is only drawn when there is something to
 * say, and "nobody is overloaded" is a claim this data cannot support — it only knows about
 * counts and ages.
 */
export function workloadNote(rows: readonly WorkloadRow[]): string | undefined {
  const worst = rows
    // The unassigned pile has no name to put in a sentence, and "nobody is carrying three
    // urgent tickets" is the wrong reading of an unowned backlog rather than a shorter one.
    .filter((row) => row.person !== undefined && row.urgentOverThreeDays > 0)
    .sort((left, right) => right.urgentOverThreeDays - left.urgentOverThreeDays)[0];
  if (worst === undefined) return undefined;

  const count = worst.urgentOverThreeDays;
  const noun = count === 1 ? "ticket" : "tickets";
  return `${worst.person!.displayName} is carrying ${count} urgent ${noun} open for more than three days.`;
}

// --- helpers ---------------------------------------------------------------

function keyOf(ticket: Ticket, groupBy: Exclude<ViewGroupBy, "none">): string {
  switch (groupBy) {
    case "status":
      return ticket.status;
    case "priority":
      return ticket.priority;
    // The first assignee only. A ticket with two owners appears once, under whoever is
    // named first, because a row drawn twice in one grouped list is a row somebody will
    // count twice.
    case "assignee":
      return ticket.assigneeIds[0] ?? "";
    case "project":
      return ticket.projectId ?? "";
  }
}

/** Unkeyed rows sort last: they are the leftovers, whatever the facet. */
function rank(key: string, groupBy: Exclude<ViewGroupBy, "none">): number {
  if (key === "") return Number.MAX_SAFE_INTEGER;
  switch (groupBy) {
    case "status":
      return indexOr(STATUS_ORDER as string[], key);
    case "priority":
      return indexOr(PRIORITY_ORDER as string[], key);
    // Nothing orders people or projects but the order they arrived in, which is the order
    // the server sorted the rows in — so a stable zero rather than an invented one.
    case "assignee":
    case "project":
      return 0;
  }
}

const indexOr = (order: string[], key: string) => {
  const at = order.indexOf(key);
  return at === -1 ? order.length : at;
};

function labelOf(
  key: string,
  groupBy: Exclude<ViewGroupBy, "none">,
  names?: { person?: (id: string) => string; project?: (id: string) => string },
): string {
  switch (groupBy) {
    case "status":
      return STATUS_LABELS[key as TicketStatus] ?? key;
    case "priority":
      return PRIORITY_LABELS[key as TicketPriority] ?? key;
    case "assignee":
      return key === "" ? "Unassigned" : (names?.person?.(key) ?? key);
    case "project":
      return key === "" ? "No project" : (names?.project?.(key) ?? key);
  }
}
