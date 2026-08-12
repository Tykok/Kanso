import { describe, expect, it } from "vitest";
import type { TimelineDependency, TimelineTicket } from "@/lib/api";
import { canMoveTicket, canSelectTicket, isContextRow, overlapNotice, rowLabel } from "./row";
import type { Row } from "./view";

/** A dependency edge, defaulting to the unbroken case so each test overrides only what it tests. */
const dep = (overrides: Partial<TimelineDependency> = {}): TimelineDependency => ({
  predecessorId: "pred-1",
  successorId: "succ-1",
  violated: false,
  overlap: false,
  outOfScope: false,
  ...overrides,
});

const noName = () => undefined;

/** A ticket row's ticket, in scope and editable by default so each case overrides only
 * the field it is about. */
const ticket = (overrides: Partial<TimelineTicket> = {}): TimelineTicket => ({
  id: "ticket-1",
  identifier: "KAN-1",
  title: "Fix the OAuth login",
  status: "todo",
  critical: false,
  late: false,
  teamKey: "KAN",
  context: false,
  editable: true,
  ...overrides,
});

describe("overlapNotice", () => {
  it("says nothing when the ticket's dependencies all hold", () => {
    expect(overlapNotice([dep()], "succ-1", noName)).toBeUndefined();
  });

  it("says nothing about another ticket's broken edges", () => {
    // The filter is on `successorId`; a violated edge belonging to some other ticket
    // must not leak a badge onto this one.
    const deps = [dep({ successorId: "someone-else", violated: true })];
    expect(overlapNotice(deps, "succ-1", noName)).toBeUndefined();
  });

  it("names the predecessor for a violated edge, not only for an overlap", () => {
    // `violated` and `overlap` are the two ways an edge can be broken; a filter that
    // narrowed to only one of them would silently stop reporting the other.
    const deps = [dep({ violated: true })];
    const nameOf = (id: string) => (id === "pred-1" ? "KAN-1" : undefined);
    expect(overlapNotice(deps, "succ-1", nameOf)).toBe("starts before KAN-1 ends");
  });

  it("names the predecessor for an overlap edge", () => {
    const deps = [dep({ overlap: true })];
    const nameOf = (id: string) => (id === "pred-1" ? "KAN-1" : undefined);
    expect(overlapNotice(deps, "succ-1", nameOf)).toBe("starts before KAN-1 ends");
  });

  it("falls back to a nameless sentence when the predecessor cannot be resolved", () => {
    // `nameOf` misses when the predecessor is outside the tickets the view carries —
    // the badge still has to say something rather than printing "undefined".
    const deps = [dep({ overlap: true })];
    expect(overlapNotice(deps, "succ-1", noName)).toBe("starts before a dependency ends");
  });

  it("counts rather than names when more than one dependency is broken", () => {
    const deps = [
      dep({ predecessorId: "pred-1", violated: true }),
      dep({ predecessorId: "pred-2", overlap: true }),
    ];
    const nameOf = (id: string) => (id === "pred-1" ? "KAN-1" : "KAN-2");
    expect(overlapNotice(deps, "succ-1", nameOf)).toBe("2 dependencies not respected");
  });
});

describe("canMoveTicket", () => {
  // All eight combinations of the three reasons a bar can be un-movable. Exactly one
  // is `true`: every gate open at once.
  const CASES: { canPlan: boolean; context: boolean; editable: boolean; expected: boolean }[] = [
    { canPlan: true, context: false, editable: true, expected: true },
    { canPlan: true, context: false, editable: false, expected: false },
    { canPlan: true, context: true, editable: true, expected: false },
    { canPlan: true, context: true, editable: false, expected: false },
    { canPlan: false, context: false, editable: true, expected: false },
    { canPlan: false, context: false, editable: false, expected: false },
    { canPlan: false, context: true, editable: true, expected: false },
    { canPlan: false, context: true, editable: false, expected: false },
  ];

  it("is true only when the chart, the row and the ticket's own team all allow it", () => {
    for (const { canPlan, context, editable, expected } of CASES) {
      expect(
        canMoveTicket({ context, editable }, canPlan),
        `canPlan=${canPlan} context=${context} editable=${editable}`,
      ).toBe(expected);
    }
  });
});

describe("canSelectTicket", () => {
  it("refuses only a context ticket, whatever canPlan or editable say", () => {
    expect(canSelectTicket({ context: false })).toBe(true);
    expect(canSelectTicket({ context: true })).toBe(false);
  });
});

describe("isContextRow", () => {
  it("reads the ticket's own flag", () => {
    expect(isContextRow({ kind: "ticket", ticket: ticket({ context: true }) })).toBe(true);
    expect(isContextRow({ kind: "ticket", ticket: ticket({ context: false }) })).toBe(false);
  });

  it("is false for a project row, which the scope filter never excludes", () => {
    const row: Row = { kind: "project", project: { id: "project-1", name: "Refonte" } };
    expect(isContextRow(row)).toBe(false);
  });
});

describe("rowLabel", () => {
  it("prints a project by its own name", () => {
    const row: Row = { kind: "project", project: { id: "project-1", name: "Refonte" } };
    expect(rowLabel(row)).toBe("Refonte");
  });

  it("prints an in-scope ticket by its identifier alone", () => {
    const row: Row = { kind: "ticket", ticket: ticket({ context: false }) };
    expect(rowLabel(row)).toBe("KAN-1");
  });

  it("prefixes a context ticket with the team it belongs to", () => {
    const row: Row = {
      kind: "ticket",
      ticket: ticket({ context: true, teamKey: "OPS", identifier: "OPS-9" }),
    };
    expect(rowLabel(row)).toBe("OPS · OPS-9");
  });
});
