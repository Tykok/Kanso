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
  it("counts the fields it guessed and the people it matched", () => {
    const summary = detailsSummary({
      mappings: { base: { columns: { title: "Name", status: "Statut" }, values: {} } },
      people: { "notion-1": "user-1", "notion-2": null },
    });

    expect(summary).toBe("2 fields guessed · 1 person matched · 1 unmatched");
  });

  /** Summed across every base, not read off one — the loop is the whole implementation. */
  it("sums the fields guessed across more than one base", () => {
    const summary = detailsSummary({
      mappings: {
        tasks: { columns: { title: "Name", status: "Statut" }, values: {} },
        docs: { columns: { title: "Titre" }, values: {} },
      },
      people: {},
    });

    expect(summary).toBe("3 fields guessed");
  });

  it("says nothing about people when no column names any", () => {
    const summary = detailsSummary({
      mappings: { base: { columns: { title: "Name" }, values: {} } },
      people: {},
    });

    expect(summary).toBe("1 field guessed");
  });

  /** A plan whose bases have no schema yet: the panel is still openable, and says so. */
  it("is empty-handed before anything has been guessed", () => {
    expect(detailsSummary({ mappings: {}, people: {} })).toBe("nothing mapped yet");
  });
});
