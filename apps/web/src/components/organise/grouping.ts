import { PRIORITY_LABELS, STATUS_LABELS } from "@/lib/status";
import { WORKFLOW_ORDER } from "@/lib/status-order";
import type {
  Ticket,
  TicketGroup as ServerGroup,
  TicketPriority,
  TicketStatus,
  ViewGroupBy,
  WorkloadRow,
} from "@/lib/api";

/**
 * How the rows are stacked on screens 19 and 21, and the sentence under screen 23.
 *
 * The server stacks and counts now; this module names. It used to do all three, and the
 * counting was the part it could not do honestly: it bucketed whatever page had been
 * fetched, so `Todo · 29` meant "twenty-nine of the two hundred rows I hold" while the
 * question had two thousand. [nameGroups] is the screen's half of the new split — the
 * server owns which buckets exist, how big they are and what order they come in, and
 * this file owns what a reader sees them called, because the names are here.
 *
 * [groupTickets] stays for the lists that are fetched whole. A cycle is a bounded set and
 * screen 19 already holds all of it, so bucketing it locally is not a page pretending to
 * be an answer. Both paths keep the one rule this module has always had: grouping never
 * reorders **inside** a group, or the view's `sortBy` silently stops working.
 */
export type Group = {
  /** The value rows were grouped on. Empty string for "these have none". */
  key: string;
  /** Empty when the answer was not to group at all. */
  label: string;
  count: number;
  tickets: Ticket[];
};

/**
 * Priority is read in one order, in one place, and its membership is the whole vocabulary,
 * so it stays here beside the only function that asks. The status order that used to sit
 * next to it did not qualify on any of the three: four screens read a status order, three
 * of them read a *different* one, and `TicketQueryRepository` renders a fifth copy into
 * SQL. It lives in `lib/status-order.ts` now — [WORKFLOW_ORDER] is the one this file
 * stacks by, and the one the server has to agree with.
 */
const PRIORITY_ORDER: TicketPriority[] = ["urgent", "high", "medium", "low", "none"];

/**
 * The one bucket key that is not a value of any facet.
 *
 * A ticket no team has claimed is in none of the rooms a `groupBy` stacks: it has no
 * status the team is working through, no project and nobody on it. `queries/core.ts`
 * folds the caller's own drafts into the unscoped list because that is the only place
 * they can be seen at all, and this is what the header over them reads.
 *
 * It cannot collide with a key the server sends: `status` and `priority` are closed
 * vocabularies, and an assignee or a project is a UUID.
 */
export const DRAFTS_GROUP = "drafts";

/**
 * The server's buckets, named for the reader — and nothing else.
 *
 * No reordering and no recounting. The order the buckets arrive in is the order the page
 * boundary was cut against, so restacking them here would put a header above rows that
 * belong under the next one; and the count is the whole match, which is a number this
 * side of the wire cannot check and must not replace with the length of what it holds.
 *
 * The one thing it does decide is that a bucket the page has not reached yet still draws
 * its header. `Done · 12` above nothing is not an empty group — it is twelve rows one
 * scroll away, and hiding the header until they load would make the list grow a heading
 * in the middle of a scroll.
 */
export function nameGroups(
  groups: readonly ServerGroup[],
  groupBy: ViewGroupBy,
  names?: { person?: (id: string) => string; project?: (id: string) => string },
): Group[] {
  if (groupBy === "none") {
    // One flat list, and no header over it — the same reading `groupTickets` gives `none`.
    return groups.map((group) => ({ ...group, label: "", tickets: [...group.tickets] }));
  }
  return groups.map((group) => ({
    ...group,
    label: labelOf(group.key, groupBy, names),
    tickets: [...group.tickets],
  }));
}

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
      return indexOr(WORKFLOW_ORDER, key);
    case "priority":
      return indexOr(PRIORITY_ORDER, key);
    // Nothing orders people or projects but the order they arrived in, which is the order
    // the server sorted the rows in — so a stable zero rather than an invented one.
    case "assignee":
    case "project":
      return 0;
  }
}

const indexOr = (order: readonly string[], key: string) => {
  const at = order.indexOf(key);
  return at === -1 ? order.length : at;
};

function labelOf(
  key: string,
  groupBy: Exclude<ViewGroupBy, "none">,
  names?: { person?: (id: string) => string; project?: (id: string) => string },
): string {
  // Before the switch, because a draft answers to no facet: whichever way the list is
  // stacked, the bucket is the same one and reads the same way.
  if (key === DRAFTS_GROUP) return "Drafts";
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
