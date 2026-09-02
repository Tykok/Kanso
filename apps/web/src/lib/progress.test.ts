import { describe, expect, it } from "vitest";
import type { DeliveredCycle, EffectiveVelocity, OpenLoad } from "./api";
import { loadSentence, MIN_FOR_A_TREND, trend } from "./progress";

/**
 * The two design traps screen 40 is built around, asserted rather than eyeballed.
 *
 * A lone bar and a sentence that reads as a grade are both things a screenshot review
 * passes and a suite catches — which is why the decisions are functions in the first place.
 */

const cycle = (number: number, points: number, extra: Partial<DeliveredCycle> = {}): DeliveredCycle => ({
  cycleId: `cycle-${number}`,
  number,
  startsOn: "2026-08-03",
  endsOn: "2026-08-07",
  points,
  workingDays: 5,
  unestimated: 0,
  countedTowardsVelocity: true,
  ...extra,
});

const measured = (rate: number, cycles = 3): EffectiveVelocity => ({
  perWorkingDay: rate,
  source: "measured",
  measured: rate,
  measuredCycles: cycles,
  cyclesUntilMeasured: 0,
});

const noPace: EffectiveVelocity = {
  source: "none",
  measuredCycles: 0,
  cyclesUntilMeasured: 2,
};

const plate = (tickets: number, points: number, unestimated = 0, workingDays?: number): OpenLoad => ({
  load: { tickets, points, unestimated },
  byStatus: {},
  byProject: [],
  workingDays,
});

describe("trend", () => {
  it("draws the chart from two closed cycles, which is the fewest that can have a direction", () => {
    const verdict = trend([cycle(21, 5), cycle(22, 9)]);
    expect(verdict).toEqual({ drawable: true, cycles: 2 });
    expect(MIN_FOR_A_TREND).toBe(2);
  });

  // The trap the ticket names first: one bar invites a reading — a direction — that one
  // point cannot support.
  it("refuses a chart on a single closed cycle", () => {
    const verdict = trend([cycle(21, 5)]);
    expect(verdict.drawable).toBe(false);
  });

  // ...but refusing the chart is not refusing the number. A screen that said "not enough
  // data" while holding a figure would be hiding it.
  it("puts the one cycle's figure into the sentence it shows instead", () => {
    const verdict = trend([cycle(21, 5)]);
    if (verdict.drawable) throw new Error("expected the waiting message");
    expect(verdict.waiting).toContain("5 points");
    expect(verdict.waiting).toContain("cycle 21");
    expect(verdict.waiting).toContain("One bar is not a trend");
  });

  it("says what it is waiting for when no cycle has closed at all, rather than nothing", () => {
    const verdict = trend([]);
    if (verdict.drawable) throw new Error("expected the waiting message");
    expect(verdict.waiting).not.toBe("");
    expect(verdict.waiting).toContain("No cycle has closed");
  });

  // A fresh instance is the common case, and an empty box is what it must never render.
  it("never answers with an empty message", () => {
    for (const delivered of [[], [cycle(21, 0)], [cycle(21, 4.5)]]) {
      const verdict = trend(delivered);
      if (verdict.drawable) throw new Error("expected the waiting message");
      expect(verdict.waiting.length).toBeGreaterThan(20);
    }
  });
});

describe("loadSentence", () => {
  it("weighs the plate in days against the pace in force", () => {
    const sentence = loadSentence(plate(2, 13, 0, 6.5), measured(2));
    expect(sentence).toContain("13 points");
    expect(sentence).toContain("2 tickets");
    expect(sentence).toContain("6.5 working days");
    expect(sentence).toContain("2 points per working day");
  });

  // The sentence is the only place the two halves of this screen meet, so it may not be
  // silently dropped when there is no pace — it has to say why it cannot say more.
  it("explains itself rather than guessing when there is no pace", () => {
    const sentence = loadSentence(plate(3, 8), noPace);
    expect(sentence).toContain("8 points");
    expect(sentence).toContain("no pace for you yet");
    expect(sentence).not.toContain("working days");
  });

  it("names what the weight cannot see, and never as a zero", () => {
    expect(loadSentence(plate(5, 8, 2, 4), measured(2))).toContain("2 of your 5 carry no estimate");
    expect(loadSentence(plate(5, 8, 0, 4), measured(2))).not.toContain("carry no estimate");
  });

  it("says the plate is empty rather than reporting nought points at some pace", () => {
    const sentence = loadSentence(plate(0, 0, 0, 0), measured(2));
    expect(sentence).toBe("Nothing open is assigned to you in this team.");
  });

  // One decimal, no more. The pace underneath is a three-cycle mean, so `6.47 days` would
  // be a precision the input cannot support and a number somebody would commit to.
  it("rounds the days to one decimal rather than printing the division", () => {
    const sentence = loadSentence(plate(1, 13, 0, 13 / 3), measured(3));
    expect(sentence).toContain("4.3 working days");
    expect(sentence).not.toContain("4.333");
  });

  it("keeps the plural of a single day and a single ticket", () => {
    const sentence = loadSentence(plate(1, 1, 0, 1), measured(1));
    expect(sentence).toContain("1 point across 1 ticket");
    expect(sentence).toContain("1 working day ");
  });

  /**
   * The second trap the ticket names: nothing on this page may read as a grade.
   *
   * A blunt assertion over the whole vocabulary rather than a review of each sentence,
   * because the failure mode is somebody adding a "you are below the team average" branch
   * six months from now with the best of intentions. Every sentence here is in the second
   * person, about this person, over time — there is no comparator to be had.
   */
  it("has no comparator, no adjective and no target in any of its branches", () => {
    const sentences = [
      loadSentence(plate(2, 13, 0, 6.5), measured(2)),
      loadSentence(plate(3, 8), noPace),
      loadSentence(plate(0, 0, 0, 0), measured(2)),
      loadSentence(plate(5, 8, 2, 4), measured(2)),
    ];
    // "team" is not on this list: naming the scope the figures were gathered over is not a
    // comparison, and the empty-plate sentence has to say which team it found nothing in.
    const forbidden = [
      "average",
      "than",
      "behind",
      "ahead",
      "target",
      "expected",
      "should",
      "good",
      "bad",
      "slow",
      "fast",
      "low",
      "high",
      "only",
    ];
    for (const sentence of sentences) {
      for (const word of forbidden) {
        expect(sentence.toLowerCase()).not.toContain(word);
      }
    }
  });
});
