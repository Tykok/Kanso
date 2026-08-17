import { describe, expect, it } from "vitest";
import type { Ticket } from "@/lib/api";
import { groupTickets, workloadNote } from "./grouping";

/**
 * How the rows are stacked on screens 19 and 21, and the one sentence under screen 23.
 *
 * The server sorts and the client groups: a grouped wire shape would be a second
 * representation of one list that could disagree with itself. So the grouping has to be
 * stable and it has to keep the server's order inside each group, which is what these
 * assert.
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

describe("workloadNote", () => {
  it("names the person carrying urgent work that has been open too long", () => {
    const rows = [
      { person: { id: "u1", displayName: "M. Rey" }, total: 7, byStatus: {}, urgentOverThreeDays: 3, oldestOpenDays: 9 },
      { person: { id: "u2", displayName: "A. Okonkwo" }, total: 4, byStatus: {}, urgentOverThreeDays: 1, oldestOpenDays: 2 },
    ];

    expect(workloadNote(rows)).toBe(
      "M. Rey is carrying 3 urgent tickets open for more than three days.",
    );
  });

  it("says it in the singular when there is one", () => {
    const rows = [
      { person: { id: "u1", displayName: "J. Salas" }, total: 2, byStatus: {}, urgentOverThreeDays: 1, oldestOpenDays: 4 },
    ];

    expect(workloadNote(rows)).toBe(
      "J. Salas is carrying 1 urgent ticket open for more than three days.",
    );
  });

  // Nothing to say is better than a sentence saying nothing is wrong. The strip is only
  // rendered when there is a note, so the empty case has to be distinguishable.
  it("says nothing when nobody is carrying anything urgent and old", () => {
    const rows = [
      { person: { id: "u1", displayName: "M. Rey" }, total: 7, byStatus: {}, urgentOverThreeDays: 0, oldestOpenDays: 1 },
    ];

    expect(workloadNote(rows)).toBeUndefined();
  });

  // The unassigned pile has no name to put in a sentence, and "nobody is carrying three
  // urgent tickets" is exactly the wrong reading of an unowned backlog.
  it("skips the unassigned pile, which is not a person to warn about", () => {
    const rows = [{ total: 6, byStatus: {}, urgentOverThreeDays: 4, oldestOpenDays: 20 }];

    expect(workloadNote(rows)).toBeUndefined();
  });
});
