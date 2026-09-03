import { describe, expect, it } from "vitest";
import type { Ticket, TicketStatus, TimelineDependency } from "@/lib/api";
import { blockages, dueBucketOf, type DueClock, type Upstream } from "./work-buckets";

/**
 * The Due and Blocked tabs' two rulings.
 *
 * Both are transcriptions of a server-side rule that also feeds the strip above them, so
 * what is asserted here is agreement: the same tickets the strip counted as overdue and
 * blocked have to be the ones these two functions put in a group.
 */

/** Monday 31 August 2026 is the Monday of the week the clock below stands in. */
const clock: DueClock = {
  day: "2026-09-02",
  at: Date.parse("2026-09-02T14:00:00Z"),
  weekStartsOn: "2026-08-31",
};

const day = (value: string) => ({ at: `${value}T00:00:00Z`, hasTime: false });
const moment = (value: string) => ({ at: value, hasTime: true });

describe("dueBucketOf", () => {
  it("has no bucket for a ticket with no deadline", () => {
    expect(dueBucketOf(undefined, clock)).toBeUndefined();
  });

  // A day-granularity date passes at the *end* of its day: a ticket due today is not late
  // at two in the afternoon, and putting an alarm on the screen of somebody who still has
  // the whole day is the bug `MyStatsService.overdue` is written to avoid.
  it("does not call today's date overdue", () => {
    expect(dueBucketOf(day("2026-09-02"), clock)).toBe("thisWeek");
  });

  it("calls yesterday overdue", () => {
    expect(dueBucketOf(day("2026-09-01"), clock)).toBe("overdue");
  });

  // A date somebody gave a time to is late at that time, to the minute. Rounding it up to
  // the end of the day would forgive an afternoon.
  it("reads a timed deadline to the minute, in both directions", () => {
    expect(dueBucketOf(moment("2026-09-02T09:00:00Z"), clock)).toBe("overdue");
    expect(dueBucketOf(moment("2026-09-02T18:00:00Z"), clock)).toBe("thisWeek");
  });

  it("puts the rest of this week in this week, to its last day", () => {
    expect(dueBucketOf(day("2026-09-04"), clock)).toBe("thisWeek");
    // Sunday, the seventh day of the ISO week the server named.
    expect(dueBucketOf(day("2026-09-06"), clock)).toBe("thisWeek");
  });

  it("puts the day after this week in later", () => {
    expect(dueBucketOf(day("2026-09-07"), clock)).toBe("later");
    expect(dueBucketOf(day("2027-01-04"), clock)).toBe("later");
  });

  // The week boundary comes from the server's own last bucket, so a reader in a week that
  // starts on a different Monday gets different groups from the same tickets — which is
  // the point: the Done tab's twelve buckets and this tab's "this week" are one week.
  it("moves its boundary with the week the server named", () => {
    const nextWeek: DueClock = { ...clock, day: "2026-09-07", weekStartsOn: "2026-09-07" };

    expect(dueBucketOf(day("2026-09-13"), nextWeek)).toBe("thisWeek");
    expect(dueBucketOf(day("2026-09-14"), nextWeek)).toBe("later");
  });
});

const ticket = (id: string, extra: Partial<Ticket> = {}): Ticket => ({
  id,
  identifier: `KAN-${id}`,
  title: `Ticket ${id}`,
  status: "todo",
  priority: "medium",
  assigneeIds: ["me"],
  docIds: [],
  customFields: {},
  archived: false,
  mirror: { state: "disabled" },
  createdAt: "2026-08-01T00:00:00Z",
  updatedAt: "2026-08-01T00:00:00Z",
  ...extra,
});

const edge = (predecessorId: string, successorId: string): TimelineDependency => ({
  predecessorId,
  successorId,
  violated: false,
  overlap: false,
  outOfScope: false,
});

const upstream = (id: string, status: TicketStatus): Upstream => ({
  id,
  identifier: `KAN-${id}`,
  title: `Ticket ${id}`,
  status,
});

const named = (...rows: Upstream[]) => new Map(rows.map((row) => [row.id, row]));

describe("blockages", () => {
  it("lists a ticket whose predecessor is still open, and names what it waits on", () => {
    const result = blockages([ticket("2")], [edge("1", "2")], named(upstream("1", "in_progress")));

    expect(result.blocked).toHaveLength(1);
    expect(result.blocked[0].ticket.id).toBe("2");
    expect(result.blocked[0].waitingOn.map((row) => row.identifier)).toEqual(["KAN-1"]);
    expect(result.unresolved).toBe(0);
  });

  it("does not list a ticket whose predecessor is finished", () => {
    expect(
      blockages([ticket("2")], [edge("1", "2")], named(upstream("1", "done"))).blocked,
    ).toEqual([]);
  });

  // Asked of the category and not of a list of names: a cancelled predecessor is a
  // decision not to do the work, so reading it as a block would leave the successor
  // waiting for something nobody will ever finish.
  it("does not treat a cancelled predecessor as a block", () => {
    expect(
      blockages([ticket("2")], [edge("1", "2")], named(upstream("1", "canceled"))).blocked,
    ).toEqual([]);
  });

  // Finish-to-start: only the arrows pointing *into* a ticket can hold it. Counting the
  // other direction would report the person holding the queue up as the person stuck in it.
  it("ignores an arrow leaving my ticket", () => {
    const result = blockages([ticket("1")], [edge("1", "2")], named(upstream("2", "todo")));

    expect(result.blocked).toEqual([]);
    expect(result.unresolved).toBe(0);
  });

  it("gathers every unfinished predecessor of one ticket", () => {
    const result = blockages(
      [ticket("3")],
      [edge("1", "3"), edge("2", "3")],
      named(upstream("1", "todo"), upstream("2", "in_review")),
    );

    expect(result.blocked[0].waitingOn.map((row) => row.id)).toEqual(["1", "2"]);
  });

  // The far end of an arrow can be missing from the read the tab is drawn from — see the
  // head of `blocked-tab.tsx`. That is counted and never guessed: a row that could not say
  // what it is waiting for would be a row with nothing on it.
  it("counts a ticket whose only predecessor nothing could name", () => {
    const result = blockages([ticket("2")], [edge("1", "2")], named());

    expect(result.blocked).toEqual([]);
    expect(result.unresolved).toBe(1);
  });

  it("lists rather than counts a ticket blocked by one named and one unnamed predecessor", () => {
    const result = blockages(
      [ticket("3")],
      [edge("1", "3"), edge("2", "3")],
      named(upstream("1", "todo")),
    );

    expect(result.blocked).toHaveLength(1);
    expect(result.unresolved).toBe(0);
  });

  it("does not count a ticket whose named predecessors are all finished", () => {
    const result = blockages(
      [ticket("3")],
      [edge("1", "3"), edge("2", "3")],
      named(upstream("1", "done"), upstream("2", "done")),
    );

    expect(result.blocked).toEqual([]);
    expect(result.unresolved).toBe(0);
  });

  it("keeps the rows in the order they arrived in", () => {
    const result = blockages(
      [ticket("a"), ticket("b"), ticket("c")],
      [edge("x", "c"), edge("x", "a")],
      named(upstream("x", "todo")),
    );

    expect(result.blocked.map((row) => row.ticket.id)).toEqual(["a", "c"]);
  });

  it("answers empty for somebody with nothing open and for a graph with no arrows", () => {
    expect(blockages([], [edge("1", "2")], named(upstream("1", "todo")))).toEqual({
      blocked: [],
      unresolved: 0,
    });
    expect(blockages([ticket("1")], [], named())).toEqual({ blocked: [], unresolved: 0 });
  });
});
