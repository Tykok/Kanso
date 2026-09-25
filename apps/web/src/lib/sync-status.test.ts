import { describe, expect, it } from "vitest";
import { remainingOf, summaryOf } from "./sync-status";

const status = (jobs: Record<string, number>, over = {}) => ({
  mirrorEnabled: true,
  bootstrapped: true,
  jobs,
  ...over,
});

describe("the sidebar's sync line", () => {
  it("counts what is waiting and what is being pushed as remaining", () => {
    expect(remainingOf(status({ pending: 3, running: 2, done: 40 }))).toBe(5);
    expect(remainingOf(undefined)).toBe(0);
  });

  it("says what is left and what failed", () => {
    expect(summaryOf(status({ pending: 3, running: 2, failed: 1 }))).toBe(
      "Notion: connected · 5 queued · 1 failed",
    );
  });

  it("says when the mirror has nowhere to write yet", () => {
    expect(summaryOf(status({ pending: 51 }, { bootstrapped: false }))).toBe(
      "Notion: not bootstrapped · 51 queued",
    );
  });

  it("says the mirror is off, and nothing before the first answer", () => {
    expect(summaryOf(status({}, { mirrorEnabled: false }))).toBe("Notion mirror off");
    expect(summaryOf(undefined)).toBe("");
  });
});
