import { describe, expect, it } from "vitest";
import { detailsSummary } from "./import-details-summary";

/**
 * The lid of the fold.
 *
 * Folding the two steps away is what makes the import two screens; the cost is that a
 * suggestion that is wrong about a base is now behind a pleat. The count is the
 * counterweight — a bad match is visible without unfolding, and unfolding is for
 * correcting it.
 */
describe("what the folded panel says about itself", () => {
  /**
   * `people` alone cannot tell "everybody is matched" from "nobody has looked yet" — a row
   * neither edited nor already confirmed is omitted from it, not `null`. This is the shape
   * a *reader's* edit produces: two of three rows touched, one left "Unmatched" on purpose.
   * `peopleTotal` is what tells the difference, and it comes from `rows.length`, not from
   * `Object.keys(people)`.
   */
  it("counts the fields it guessed and separates matched from unmatched", () => {
    const summary = detailsSummary({
      mappings: { base: { columns: { title: "Name", status: "Statut" }, values: {} } },
      people: { "notion-1": "user-1", "notion-2": null },
      peopleTotal: 2,
    });

    expect(summary).toBe("2 fields guessed · 2 people met · 1 matched · 1 unmatched");
  });

  /**
   * The case finding 3 is about: a workspace Kanso has never matched before. Every row is
   * omitted from `people` — nobody has edited or confirmed anything — so a denominator read
   * off `people`'s own keys would see nothing and say nothing, which is exactly the silent
   * failure this lid exists to catch.
   */
  it("still counts the people nobody has matched, not only the ones in `people`", () => {
    const summary = detailsSummary({
      mappings: {},
      people: {},
      peopleTotal: 3,
    });

    expect(summary).toBe("3 people met · 0 matched · 3 unmatched");
  });

  /** Summed across every base, not read off one — the loop is the whole implementation. */
  it("sums the fields guessed across more than one base", () => {
    const summary = detailsSummary({
      mappings: {
        tasks: { columns: { title: "Name", status: "Statut" }, values: {} },
        docs: { columns: { title: "Titre" }, values: {} },
      },
      people: {},
      peopleTotal: 0,
    });

    expect(summary).toBe("3 fields guessed");
  });

  it("says nothing about people when nobody was met", () => {
    const summary = detailsSummary({
      mappings: { base: { columns: { title: "Name" }, values: {} } },
      people: {},
      peopleTotal: 0,
    });

    expect(summary).toBe("1 field guessed");
  });

  /** A plan whose bases have no schema yet: the panel is still openable, and says so. */
  it("is empty-handed before anything has been guessed", () => {
    expect(detailsSummary({ mappings: {}, people: {}, peopleTotal: 0 })).toBe("nothing mapped yet");
  });
});
