import { describe, expect, it } from "vitest";
import { barAccessibleName } from "./bar-style";

/** A ticket bar's name, in its most ordinary state, so each case overrides only the
 * field it is about — same shape as `barAccessibleName`'s own argument. */
const name = (overrides: Partial<Parameters<typeof barAccessibleName>[0]> = {}) =>
  barAccessibleName({ name: "KAN-1: Fix the OAuth login", state: "normal", ...overrides });

describe("barAccessibleName", () => {
  it("says nothing about slack when there is none to report", () => {
    // `slackMinutes` absent — a ticket with no dependencies — must not add an empty
    // clause or the word "slack" at all.
    expect(name()).toBe("KAN-1: Fix the OAuth login");
  });

  it("says nothing about slack when it has run out", () => {
    // Zero is not "no slack to report", but the strip draws nothing for it either
    // (see `slackWidthPx`), so the name must stay silent about it the same way.
    expect(name({ slackMinutes: 0 })).toBe("KAN-1: Fix the OAuth login");
  });

  it("names the slack in the same words as the strip's own title", () => {
    // Two days, rounded — `slackTitle`'s own granularity, not the exact minute count.
    expect(name({ slackMinutes: 2 * 1440 })).toBe("KAN-1: Fix the OAuth login — 2 days of slack");
  });

  /**
   * The defect this whole split came from: a bar fed by negative slack was announced as
   * `overdue`, so a screen reader said a ticket was late when its due date was three weeks
   * away. No bar state may say that word — whether a deadline has gone by is a pill on the
   * row's name, and a bar only ever describes the schedule.
   */
  it("never says a bar is overdue, in any state", () => {
    for (const state of ["normal", "critical", "slipping"] as const) {
      expect(name({ state })).not.toContain("overdue");
    }
  });

  it("calls a hatched bar a forecast, in words", () => {
    expect(name({ state: "slipping" })).toBe("KAN-1: Fix the OAuth login — projected to slip");
  });

  it("appends slack after the clauses that already exist", () => {
    // A violated dependency and slack are two separate facts about the same bar; a
    // reader losing one of them because a formatter only made room for the other is
    // the exact bug this covers.
    expect(name({ violated: true, slackMinutes: 3 * 1440 })).toBe(
      "KAN-1: Fix the OAuth login — dependency not respected — 3 days of slack",
    );
  });
});
