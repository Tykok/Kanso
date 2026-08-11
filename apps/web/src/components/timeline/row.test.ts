import { describe, expect, it } from "vitest";
import type { TimelineDependency } from "@/lib/api";
import { overlapNotice } from "./row";

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
