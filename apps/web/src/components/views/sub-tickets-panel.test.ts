import { describe, expect, it } from "vitest";
import { progressLabel } from "./sub-tickets-panel";

describe("progressLabel", () => {
  it("prints the count on its own when the parent is not fully estimated", () => {
    expect(progressLabel({ total: 3, done: 2, donePoints: null, totalPoints: null })).toBe(
      "2 of 3 done",
    );
  });

  it("prints the count on its own when the points are absent rather than null", () => {
    // The regression this file exists for. The server omits a null field instead of
    // serialising it, so an unestimated parent arrives with neither key — and a `=== null`
    // check let it through to render "2 of 3 done · undefined of undefined pts" in the
    // browser. Found by opening the page, not by the suite.
    expect(progressLabel({ total: 3, done: 2 })).toBe("2 of 3 done");
  });

  it("adds the points when every child is estimated", () => {
    expect(progressLabel({ total: 2, done: 1, donePoints: 3, totalPoints: 8 })).toBe(
      "1 of 2 done · 3 of 8 pts",
    );
  });

  it("never prints a percentage", () => {
    // KAN-40's rule: "60 %" against a job broken into five pieces is a mark out of ten
    // with the working hidden. The fraction is the same information, checkable.
    const label = progressLabel({ total: 5, done: 3, donePoints: 6, totalPoints: 10 });
    expect(label).not.toContain("%");
  });

  it("says nothing odd about a parent whose every child is done", () => {
    expect(progressLabel({ total: 4, done: 4 })).toBe("4 of 4 done");
  });
});
