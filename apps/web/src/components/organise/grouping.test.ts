import { describe, expect, it } from "vitest";
import type { Ticket, TicketGroup, WorkloadRow } from "@/lib/api";
import { groupTickets, nameGroups, workloadNote } from "./grouping";

/**
 * How the rows are stacked on screens 19 and 21, and the one sentence under screen 23.
 *
 * The server stacks and counts; this file names. `groupTickets` is still the path for the
 * lists that are fetched whole — a cycle is bounded and screen 19 holds all of it — and it
 * has to keep the server's order inside each group, which is what its own block asserts.
 * `nameGroups` is the other path, and everything worth asserting about it is what it
 * refuses to do: it does not reorder, and it does not recount.
 */
const ticket = (overrides: Partial<Ticket> = {}): Ticket =>
  ({
    id: overrides.id ?? "t1",
    identifier: "KAN-1",
    number: 1,
    teamId: "team",
    title: "Echo suppression drops our own writes",
    status: "todo",
    priority: "none",
    assigneeIds: [],
    docIds: [],
    archived: false,
    mirror: { state: "synced" },
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    ...overrides,
  }) as Ticket;

describe("groupTickets", () => {
  it("stacks the statuses in the order the work flows, not alphabetically", () => {
    const rows = [
      ticket({ id: "a", status: "done" }),
      ticket({ id: "b", status: "backlog" }),
      ticket({ id: "c", status: "in_progress" }),
    ];

    expect(groupTickets(rows, "status").map((group) => group.key)).toEqual([
      "backlog",
      "in_progress",
      "done",
    ]);
  });

  it("labels a group as the screen prints it, with its own count", () => {
    const rows = [ticket({ id: "a", status: "in_progress" }), ticket({ id: "b", status: "in_progress" })];

    expect(groupTickets(rows, "status")[0]).toMatchObject({
      key: "in_progress",
      label: "In progress",
      count: 2,
    });
  });

  // The server has already sorted by the view's `sortBy`. Grouping must not resort inside a
  // group, or the sort control would silently stop working for any grouped view.
  it("keeps the order the server sent inside each group", () => {
    const rows = [
      ticket({ id: "second", status: "todo" }),
      ticket({ id: "first", status: "todo" }),
    ];

    expect(groupTickets(rows, "status")[0].tickets.map((t) => t.id)).toEqual(["second", "first"]);
  });

  it("has no empty groups, so nothing draws a header over nothing", () => {
    expect(groupTickets([ticket({ status: "todo" })], "status")).toHaveLength(1);
  });

  it("groups by priority, urgent first", () => {
    const rows = [ticket({ id: "a", priority: "low" }), ticket({ id: "b", priority: "urgent" })];

    expect(groupTickets(rows, "priority").map((group) => group.key)).toEqual(["urgent", "low"]);
  });

  it("puts everyone with no assignee in one named group rather than in none", () => {
    const rows = [ticket({ id: "a", assigneeIds: [] }), ticket({ id: "b", assigneeIds: ["u1"] })];
    const groups = groupTickets(rows, "assignee");

    expect(groups.map((group) => group.key)).toEqual(["u1", ""]);
    expect(groups[1].label).toBe("Unassigned");
  });

  // `none` is a real choice on the group-by control, and it has to give one flat list — not
  // zero groups, which would render as an empty screen.
  it("returns one unlabelled group when the answer is not to group", () => {
    const groups = groupTickets([ticket(), ticket({ id: "b" })], "none");

    expect(groups).toHaveLength(1);
    expect(groups[0].label).toBe("");
    expect(groups[0].count).toBe(2);
  });

  it("returns nothing for nothing", () => {
    expect(groupTickets([], "status")).toEqual([]);
  });
});

describe("nameGroups", () => {
  const group = (over: Partial<TicketGroup> = {}): TicketGroup => ({
    key: "todo",
    count: 1,
    tickets: [ticket()],
    ...over,
  });

  it("labels a bucket as the screen prints it", () => {
    expect(nameGroups([group({ key: "in_progress" })], "status")[0].label).toBe("In progress");
  });

  /**
   * The count is the whole match and the rows are a page of it, and the gap between the
   * two is the entire reason the wire carries buckets. Replacing it with `tickets.length`
   * is the bug this move was made to fix, so it is asserted as a refusal.
   */
  it("keeps the server's count even when it is bigger than the rows it holds", () => {
    const named = nameGroups([group({ key: "todo", count: 29, tickets: [ticket()] })], "status");

    expect(named[0].count).toBe(29);
    expect(named[0].tickets).toHaveLength(1);
  });

  /**
   * The buckets arrive in the order the page boundary was cut against — restacking them
   * here would draw a header above rows belonging under the next one. `done` before
   * `backlog` is not an order this screen would choose; it is the one it was given.
   */
  it("keeps the order the server sent, even one it would not have chosen", () => {
    const sent = [group({ key: "done" }), group({ key: "backlog" })];

    expect(nameGroups(sent, "status").map((one) => one.key)).toEqual(["done", "backlog"]);
  });

  /** A bucket the page has not reached is twelve rows away, not an empty group. */
  it("draws a header for a bucket whose rows have not arrived", () => {
    const named = nameGroups([group({ key: "done", count: 12, tickets: [] })], "status");

    expect(named[0]).toMatchObject({ label: "Done", count: 12 });
  });

  it("names the leftovers rather than leaving them blank", () => {
    const [people] = nameGroups([group({ key: "" })], "assignee");
    const [projects] = nameGroups([group({ key: "" })], "project");

    expect(people.label).toBe("Unassigned");
    expect(projects.label).toBe("No project");
  });

  it("resolves a person and a project through the names the screen holds", () => {
    const names = { person: () => "A. Okonkwo", project: () => "Design system" };

    expect(nameGroups([group({ key: "u1" })], "assignee", names)[0].label).toBe("A. Okonkwo");
    expect(nameGroups([group({ key: "p1" })], "project", names)[0].label).toBe("Design system");
  });

  // `none` is one flat list, and a blank caption over it is what `flatten` reads as "no
  // header" — so the label has to be empty rather than the key's name.
  it("writes no label for the bucket that is not a grouping", () => {
    expect(nameGroups([group({ key: "", count: 2 })], "none")[0].label).toBe("");
  });

  it("returns nothing for nothing", () => {
    expect(nameGroups([], "status")).toEqual([]);
  });
});

describe("workloadNote", () => {
  /**
   * The note is about counts and ages, so every row here leaves the load unsized: a
   * sentence that changed with the estimate would be a second reading of the chart, and
   * this one deliberately is not.
   */
  const carrying = (row: Omit<WorkloadRow, "points" | "unestimated">): WorkloadRow => ({
    ...row,
    points: 0,
    unestimated: row.total,
  });

  it("names the person carrying urgent work that has been open too long", () => {
    const rows = [
      carrying({ person: { id: "u1", displayName: "M. Rey" }, total: 7, byStatus: {}, urgentOverThreeDays: 3, oldestOpenDays: 9 }),
      carrying({ person: { id: "u2", displayName: "A. Okonkwo" }, total: 4, byStatus: {}, urgentOverThreeDays: 1, oldestOpenDays: 2 }),
    ];

    expect(workloadNote(rows)).toBe(
      "M. Rey is carrying 3 urgent tickets open for more than three days.",
    );
  });

  it("says it in the singular when there is one", () => {
    const rows = [
      carrying({ person: { id: "u1", displayName: "J. Salas" }, total: 2, byStatus: {}, urgentOverThreeDays: 1, oldestOpenDays: 4 }),
    ];

    expect(workloadNote(rows)).toBe(
      "J. Salas is carrying 1 urgent ticket open for more than three days.",
    );
  });

  // Nothing to say is better than a sentence saying nothing is wrong. The strip is only
  // rendered when there is a note, so the empty case has to be distinguishable.
  it("says nothing when nobody is carrying anything urgent and old", () => {
    const rows = [
      carrying({ person: { id: "u1", displayName: "M. Rey" }, total: 7, byStatus: {}, urgentOverThreeDays: 0, oldestOpenDays: 1 }),
    ];

    expect(workloadNote(rows)).toBeUndefined();
  });

  // The unassigned pile has no name to put in a sentence, and "nobody is carrying three
  // urgent tickets" is exactly the wrong reading of an unowned backlog.
  it("skips the unassigned pile, which is not a person to warn about", () => {
    const rows = [carrying({ total: 6, byStatus: {}, urgentOverThreeDays: 4, oldestOpenDays: 20 })];

    expect(workloadNote(rows)).toBeUndefined();
  });

  it("lets the caller name a status bucket, since the word is the team's — KAN-28", () => {
    const named = nameGroups(
      [{ key: "done", count: 3, tickets: [] }],
      "status",
      { status: (key) => (key === "done" ? "Livré" : key) },
    );

    // `STATUS_LABELS` is Kanso's word and stays the fallback; a team that renamed `done`
    // reads its own, and a scope spanning teams passes a category's name through here too.
    expect(named[0].label).toBe("Livré");
  });

  it("still names a status itself when no resolver is handed over", () => {
    const named = nameGroups([{ key: "in_review", count: 1, tickets: [] }], "status");

    expect(named[0].label).toBe("In review");
  });
});
