import { describe, expect, it } from "vitest";
import type { CycleTime, CycleTimePoint, Wip } from "./api";
import {
  cycleTimeSentence,
  cycleTimeTrend,
  duration,
  durationScale,
  teamCycleTimeSentence,
  wipSentence,
} from "./insights";
import { MIN_FOR_A_TREND } from "./progress";
import { about } from "./voice";

/**
 * KAN-23's refusals, asserted rather than eyeballed.
 *
 * The one this file exists for is the absent field. The server omits nulls, so every
 * optional number below arrives as `undefined` rather than as `null` — and a branch that
 * forgot it would print the string `undefined` or an `NaN` into a sentence, which is exactly
 * how `Last used Invalid Date` reached the tokens screen. So there is a test per shape that
 * asserts no sentence on this feature ever contains either word, whatever is missing.
 */

const measured = (medianHours: number, extra: Partial<CycleTime> = {}): CycleTime => ({
  medianHours,
  measured: 4,
  unmeasured: 0,
  ...extra,
});

/** The shape the wire actually sends when nothing could be measured: no `medianHours` key. */
const unmeasurable = (unmeasured = 0): CycleTime => ({ measured: 0, unmeasured });

const point = (number: number, cycleTime: CycleTime): CycleTimePoint => ({
  cycleId: `cycle-${number}`,
  number,
  cycleTime,
});

const inFlight = (tickets: number, extra: Partial<Wip> = {}): Wip => ({
  load: { tickets, points: tickets * 2, unestimated: 0 },
  medianAgeHours: 24,
  oldestAgeHours: 72,
  unmeasured: 0,
  ...extra,
});

describe("duration", () => {
  it("says minutes under an hour, because nobody thinks in 0.3 hours", () => {
    expect(duration(0.5)).toBe("30 minutes");
    expect(duration(1 / 60)).toBe("1 minute");
  });

  it("rounds up to a minute rather than saying nothing happened", () => {
    // Two tickets opened and closed in the same breath is a real shape — a typo fixed on
    // the spot — and `0 minutes` would read as a boast about instantaneous delivery.
    expect(duration(0.001)).toBe("1 minute");
  });

  it("says hours up to two days, which is the range a reader can picture", () => {
    expect(duration(1)).toBe("1 hour");
    expect(duration(31)).toBe("31 hours");
    expect(duration(47.6)).toBe("48 hours");
  });

  it("says days beyond that, to one decimal and no more", () => {
    // One decimal, for the reason `lib/progress.ts` gives its own days helper: the input is
    // a median over a handful of tickets, so `6.47 days` is a precision the sample cannot
    // support and the kind of number somebody puts in a commitment.
    expect(duration(48)).toBe("2 days");
    expect(duration(155)).toBe("6.5 days");
    expect(duration(24 * 30)).toBe("30 days");
  });

  it("agrees its own plural", () => {
    expect(duration(24 * 1 + 24)).toBe("2 days");
    expect(duration(24)).toBe("24 hours");
  });
});

describe("durationScale", () => {
  it("puts a whole series in one unit, chosen off its tallest bar", () => {
    // The bug a screenshot caught: labelled with `duration`, this series read "7.6 days",
    // "45 hours", "38 hours" — three units in one axis, so the reader compares 45 against
    // 7.6 and gets the ranking backwards.
    const scale = durationScale([183, 45, 37.5]);
    expect(scale.unit).toBe("days");
    expect([183, 45, 37.5].map(scale.format)).toEqual(["7.6", "1.9", "1.6"]);
  });

  it("stays in hours for a series that never reaches two days", () => {
    const scale = durationScale([45, 37.5, 26]);
    expect(scale.unit).toBe("hours");
    expect([45, 37.5, 26].map(scale.format)).toEqual(["45", "38", "26"]);
  });

  it("uses the same threshold as the prose, so a caption and a sentence agree", () => {
    expect(durationScale([47.9]).unit).toBe("hours");
    expect(durationScale([48]).unit).toBe("days");
    expect(duration(47.9)).toContain("hours");
    expect(duration(48)).toContain("days");
  });

  it("picks a unit for an empty series without reaching -Infinity", () => {
    // `Math.max()` of nothing is `-Infinity`, which is how a chart renders as nothing with
    // no error anywhere — the trap `burndown.ts` guards with the same zero.
    const scale = durationScale([]);
    expect(scale.unit).toBe("hours");
    expect(scale.format(0)).toBe("0");
  });
});

describe("cycleTimeSentence", () => {
  it("says what half the tickets took, and over how many", () => {
    const said = cycleTimeSentence(measured(48));
    expect(said).toContain("2 days or less");
    expect(said).toContain("Measured over 4 tickets");
  });

  it("names the tickets it could not see, in the same breath as the number", () => {
    // A median over four of eleven is a different claim from a median over eleven, and the
    // reader has to be told which while they are looking at it — not in a footnote.
    const said = cycleTimeSentence(measured(48, { measured: 4, unmeasured: 7 }));
    expect(said).toContain("7 more never recorded a start");
  });

  it("tells an absent median from an absent record of one", () => {
    // Two genuinely different situations, and a screen that showed one sentence for both
    // would point the reader at the wrong fix.
    expect(cycleTimeSentence(unmeasurable())).toContain("Nothing has been delivered");
    expect(cycleTimeSentence(unmeasurable(3))).toContain("never recorded a start");
    expect(cycleTimeSentence(unmeasurable(3))).toContain("moved straight to done");
  });

  it("swaps pronouns for a colleague and nothing else", () => {
    const said = cycleTimeSentence(measured(48), about("Ana Ruiz"));
    expect(said).toContain("Half of their delivered tickets");
    expect(said).not.toContain("your");
  });

  it("carries no grade, no target and no comparison", () => {
    // The head of `lib/insights.ts` states this as a design constraint: cycle time is the
    // figure most easily read as a score, because lower obviously looks better.
    for (const said of [
      cycleTimeSentence(measured(2)),
      cycleTimeSentence(measured(24 * 40)),
      cycleTimeSentence(unmeasurable(2)),
    ]) {
      expect(said).not.toMatch(/good|bad|slow|fast|worse|better|target|average|should/i);
    }
  });
});

describe("teamCycleTimeSentence", () => {
  it("names the team rather than putting a possessive on it", () => {
    const said = teamCycleTimeSentence(measured(72), "Mobile");
    expect(said).toContain("tickets Mobile delivered");
    expect(said).toContain("3 days or less");
  });

  it("still says something when there is nothing to say", () => {
    expect(teamCycleTimeSentence(unmeasurable(), "Mobile")).toContain("delivered nothing");
    expect(teamCycleTimeSentence(unmeasurable(2), "Mobile")).toContain("never recorded a start");
  });
});

describe("wipSentence", () => {
  it("counts what is in flight and names the oldest, which is the actionable half", () => {
    const said = wipSentence(inFlight(5));
    expect(said).toContain("holding 5 tickets in flight");
    expect(said).toContain("The oldest has been in flight 3 days");
    expect(said).toContain("half of them 24 hours or less");
  });

  it("does not say the same duration twice", () => {
    // "the oldest has been in flight 3 days, and half of them 3 days or less" is one
    // sentence saying one thing twice. Compared after formatting, because two figures three
    // minutes apart print identically.
    const said = wipSentence(inFlight(2, { medianAgeHours: 71.9, oldestAgeHours: 72 }));
    expect(said).toContain("The oldest has been in flight 3 days.");
    expect(said).not.toContain("half of them");
  });

  it("says an empty plate plainly rather than leaving a gap", () => {
    expect(wipSentence(inFlight(0))).toBe(
      "You are not holding anything in progress or in review.",
    );
  });

  it("counts tickets it cannot age, and says it cannot age them", () => {
    const said = wipSentence(
      inFlight(3, { medianAgeHours: undefined, oldestAgeHours: undefined }),
    );
    expect(said).toContain("holding 3 tickets in flight");
    expect(said).toContain("None of them recorded when work started");
  });

  it("names the unsized part of the weight, never as a zero", () => {
    expect(wipSentence(inFlight(4, { load: { tickets: 4, points: 6, unestimated: 2 } }))).toContain(
      "2 of them carry no estimate",
    );
    expect(wipSentence(inFlight(4))).not.toContain("carry no estimate");
  });

  it("reads for a team, because the verb is `holding` and not `assigned to`", () => {
    // `teamLoadSentence` needed a sentence of its own because "nothing open is assigned to
    // Mobile" is false of a team's plate. "Mobile is holding nothing in flight" is true of
    // exactly those tickets, so this one swaps pronouns and does not fork.
    expect(wipSentence(inFlight(3), about("Mobile"))).toContain("Mobile is holding 3 tickets");
    expect(wipSentence(inFlight(0), about("Mobile"))).toBe(
      "Mobile is not holding anything in progress or in review.",
    );
  });

  it("carries no WIP limit and no opinion about the right number", () => {
    // A WIP limit is a decision a team takes about itself. A tool that shipped one would be
    // grading a board it knows nothing about.
    for (const said of [wipSentence(inFlight(1)), wipSentence(inFlight(40))]) {
      expect(said).not.toMatch(/too many|limit|should|target|reduce/i);
    }
  });
});

describe("cycleTimeTrend", () => {
  it("draws from two measurable cycles, on screen 40's own threshold", () => {
    const verdict = cycleTimeTrend([point(21, measured(24)), point(22, measured(48))]);
    expect(verdict).toEqual({ drawable: true, cycles: 2 });
    // The same constant, not a second one: two thresholds would let this chart appear while
    // the delivered-points chart above it showed a waiting message, over identical history.
    expect(MIN_FOR_A_TREND).toBe(2);
  });

  it("counts cycles that can be measured, not cycles", () => {
    // Six closed cycles with one delivered ticket between them is one point, and five
    // hatched gaps are not five sixths of a trend.
    const verdict = cycleTimeTrend([
      point(20, unmeasurable()),
      point(21, unmeasurable()),
      point(22, measured(24)),
    ]);
    expect(verdict.drawable).toBe(false);
  });

  it("puts the one cycle's figure into the waiting message rather than withholding it", () => {
    const verdict = cycleTimeTrend([point(21, unmeasurable()), point(22, measured(30))]);
    expect(verdict.drawable).toBe(false);
    if (verdict.drawable) return;
    expect(verdict.waiting).toContain("30 hours in cycle 22");
    expect(verdict.waiting).toContain("One bar is not a trend");
  });

  it("says what it is waiting for when nothing can be measured at all", () => {
    const verdict = cycleTimeTrend([point(21, unmeasurable(2))]);
    expect(verdict.drawable).toBe(false);
    if (verdict.drawable) return;
    expect(verdict.waiting).toContain("at least one delivered ticket whose start was recorded");
  });

  it("is not drawable on an empty series, and does not throw on one", () => {
    // The empty dataset. `Math.max` over no arguments returns `-Infinity`, which is how a
    // chart renders as nothing at all with no error anywhere.
    expect(cycleTimeTrend([]).drawable).toBe(false);
  });
});

/**
 * The absent-field guard, over every sentence this feature can produce.
 *
 * Jackson omits nulls, so `medianHours`, `medianAgeHours` and `oldestAgeHours` are *missing*
 * keys and not `null` ones. A branch that read one without checking would interpolate
 * `undefined` into prose or divide it into an `NaN`, and both render as words on the page
 * rather than as an error anywhere.
 */
describe("nothing prints undefined or NaN, whatever the wire leaves out", () => {
  const shapes: CycleTime[] = [unmeasurable(), unmeasurable(9), measured(0.4), measured(24 * 90)];
  const plates: Wip[] = [
    inFlight(0),
    inFlight(3, { medianAgeHours: undefined, oldestAgeHours: undefined }),
    inFlight(3, { medianAgeHours: undefined }),
    inFlight(3, { unmeasured: 2 }),
  ];

  it("holds for every cycle-time sentence", () => {
    for (const shape of shapes) {
      for (const said of [
        cycleTimeSentence(shape),
        cycleTimeSentence(shape, about("Ana")),
        teamCycleTimeSentence(shape, "Mobile"),
      ]) {
        expect(said).not.toMatch(/undefined|NaN|Infinity/);
        expect(said.length).toBeGreaterThan(0);
      }
    }
  });

  it("holds for every work-in-flight sentence", () => {
    for (const plate of plates) {
      for (const said of [wipSentence(plate), wipSentence(plate, about("Ana"))]) {
        expect(said).not.toMatch(/undefined|NaN|Infinity/);
        expect(said.length).toBeGreaterThan(0);
      }
    }
  });

  it("holds for the trend's waiting messages", () => {
    for (const series of [[], [point(21, unmeasurable())], [point(21, unmeasurable(4))]]) {
      const verdict = cycleTimeTrend(series);
      if (verdict.drawable) continue;
      expect(verdict.waiting).not.toMatch(/undefined|NaN|Infinity/);
    }
  });
});
