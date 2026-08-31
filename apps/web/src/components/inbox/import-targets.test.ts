import { describe, expect, it } from "vitest";
import { FIELD_LABELS, TARGET_LABELS, pageCount } from "./import-targets";
import { DEFAULT_TARGET } from "./import-map";

describe("how a page count is printed", () => {
  it("prints a finished count as itself", () => {
    expect(pageCount(248, true)).toBe("248 pages");
  });

  it("prints a bounded count as at least that many", () => {
    // Notion answers no total for a data source, so the server walks the pages and stops
    // at its bound. "2000 pages" about a base holding nine thousand is the number this
    // exists to keep off a confirm button.
    expect(pageCount(2000, false)).toBe("2000+ pages");
  });

  it("has a word for every answer the mapping can take", () => {
    expect(Object.keys(TARGET_LABELS).sort()).toEqual([
      "documents",
      "ignore",
      "projects",
      "teams",
      "tickets",
    ]);
    expect(TARGET_LABELS[DEFAULT_TARGET]).toBe("Ignore");
  });

  /**
   * The same pin for the fields, and for the same reason: a sixteenth `ImportField` with no
   * word here renders as its raw wire spelling in front of a reader — `parentTeam`, not
   * "Parent team" — and nothing else in the app would fail. The type catches a missing key
   * at build time; this catches the list drifting away from the wire's.
   */
  it("has a word for every field a column can be mapped onto", () => {
    expect(Object.keys(FIELD_LABELS).sort()).toEqual([
      "assignees",
      "blockedBy",
      "description",
      "due",
      "end",
      "lead",
      "parentTeam",
      "priority",
      "project",
      "projects",
      "start",
      "status",
      "subTeams",
      "team",
      "tickets",
    ]);
    expect(Object.values(FIELD_LABELS).every((label) => label.length > 0)).toBe(true);
  });
});
