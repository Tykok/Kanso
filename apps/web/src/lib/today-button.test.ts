import { describe, expect, it } from "vitest";

import { PX_PER_DAY, scrollToToday, todayInView } from "./timeline-geometry";

describe("todayInView", () => {
  const width = 1000;

  it("is true when the rule is comfortably inside the window", () => {
    expect(todayInView(500, 0, width, "day")).toBe(true);
  });

  it("is false when the rule is left of the window", () => {
    expect(todayInView(100, 400, width, "day")).toBe(false);
  });

  it("is false when the rule is right of the window", () => {
    expect(todayInView(2000, 0, width, "day")).toBe(false);
  });

  /**
   * A whole column of tolerance at each edge. A rule one pixel inside the viewport is
   * technically visible and practically not, and a button that greyed out in that state
   * would look broken to somebody who can see the problem it refuses to fix.
   */
  it("treats a rule flush against an edge as out of view", () => {
    expect(todayInView(0, 0, width, "day")).toBe(false);
    expect(todayInView(width, 0, width, "day")).toBe(false);
    expect(todayInView(PX_PER_DAY.day, 0, width, "day")).toBe(true);
  });

  it("scales its tolerance with the zoom", () => {
    // A month column is three pixels wide, so the same near-edge x is inside at that zoom
    // and outside at day zoom, which is the whole reason the margin is not a constant.
    expect(todayInView(5, 0, width, "month")).toBe(true);
    expect(todayInView(5, 0, width, "day")).toBe(false);
  });
});

describe("scrollToToday", () => {
  it("centres the rule rather than putting it at an edge", () => {
    expect(scrollToToday(2000, 1000)).toBe(1500);
  });

  it("never scrolls past the start", () => {
    expect(scrollToToday(100, 1000)).toBe(0);
  });
});
