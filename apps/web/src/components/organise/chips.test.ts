import { describe, expect, it } from "vitest";
import type { ViewFilters } from "@/lib/api";
import { chipsOf, withoutChip } from "./chips";

/**
 * The removable filter chips on screen 21, and what the `×` on one does.
 *
 * The drawing shows three: `Projet Miroir Notion`, `Statut ≠ Done`, `Étiquette synchro`.
 * The third is not testable here and not buildable at all — the server refuses a `label`
 * key until slice 0's `V8` lands — so these cover the two that exist plus the facets the
 * saved-view list implies (`Sans assigné`, `Bloqués depuis 3 j`).
 */
const names = {
  project: (id: string) => (id === "p1" ? "Notion mirror" : id),
  person: (id: string) => (id === "u1" ? "M. Rey" : id),
  cycle: (id: string) => (id === "c1" ? "Cycle 24" : id),
};

describe("chipsOf", () => {
  it("draws a chip per facet, with the facet as the label and the value beside it", () => {
    const filters: ViewFilters = { project: ["p1"], statusNot: ["done"] };

    expect(chipsOf(filters, names)).toEqual([
      { key: "project", label: "Project", value: "Notion mirror" },
      { key: "statusNot", label: "Status", value: "≠ Done" },
    ]);
  });

  // `Statut ≠ Done` is the drawing's own wording, and the ≠ is the whole point: a chip
  // reading "Status Done" on a list of everything that is not done would be backwards.
  it("spells an exclusion with the sign the drawing uses", () => {
    expect(chipsOf({ statusNot: ["done", "canceled"] }, names)[0].value).toBe("≠ Done, Canceled");
  });

  it("joins several values of one facet into one chip rather than one chip each", () => {
    expect(chipsOf({ priority: ["urgent", "high"] }, names)).toEqual([
      { key: "priority", label: "Priority", value: "Urgent, High" },
    ]);
  });

  // A boolean facet has no value to print, so the chip is the label alone. "Unassigned
  // true" would read as a database row rather than as a question.
  it("prints a boolean facet as a bare label", () => {
    expect(chipsOf({ unassigned: true }, names)).toEqual([
      { key: "unassigned", label: "Unassigned", value: "" },
    ]);
  });

  it("says nothing about a boolean that is false, or an empty list", () => {
    expect(chipsOf({ unassigned: false, status: [] }, names)).toEqual([]);
  });

  it("reads the drawing's saved view names back as chips", () => {
    expect(chipsOf({ openedForDays: 3 }, names)).toEqual([
      { key: "openedForDays", label: "Open for", value: "more than 3 days" },
    ]);
  });

  it("resolves an id to the name a reader would recognise", () => {
    expect(chipsOf({ assignee: ["u1"], cycle: ["c1"] }, names)).toEqual([
      { key: "assignee", label: "Assignee", value: "M. Rey" },
      { key: "cycle", label: "Cycle", value: "Cycle 24" },
    ]);
  });

  // An unknown id still gets a chip. Falling back to the raw id is ugly, but dropping the
  // chip would hide a filter that is still narrowing the list.
  it("falls back to the raw id rather than dropping the chip", () => {
    expect(chipsOf({ project: ["gone"] }, names)[0].value).toBe("gone");
  });
});

describe("withoutChip", () => {
  it("removes one facet and leaves the others", () => {
    expect(withoutChip({ project: ["p1"], statusNot: ["done"] }, "project")).toEqual({
      statusNot: ["done"],
    });
  });

  // The key has to be *absent*, not present and empty: the server validates the keys it is
  // sent, and `{ project: [] }` would be a chip the screen no longer draws still travelling
  // on every write.
  it("deletes the key rather than emptying it", () => {
    expect(Object.keys(withoutChip({ project: ["p1"] }, "project"))).toEqual([]);
  });

  it("does not mutate the filters it was given", () => {
    const filters: ViewFilters = { project: ["p1"], unassigned: true };
    withoutChip(filters, "project");
    expect(filters.project).toEqual(["p1"]);
  });
});
