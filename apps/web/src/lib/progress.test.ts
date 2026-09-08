import { describe, expect, it } from "vitest";
import type { DeliveredCycle, EffectiveVelocity, OpenLoad } from "./api";
import { loadSentence, MIN_FOR_A_TREND, readersSentence, teamLoadSentence, trend } from "./progress";
import { about } from "./voice";

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
  buckets: [],
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

describe("loadSentence, aimed at somebody else", () => {
  const ana = about("Ana Ruiz");

  it("names the subject and agrees the verb, rather than addressing the reader", () => {
    const sentence = loadSentence(plate(2, 13, 0, 6.5), measured(2), ana);
    expect(sentence).toContain("Ana Ruiz is carrying 13 points");
    expect(sentence).not.toContain("You are");
  });

  it("says nothing in the second person in any branch", () => {
    const branches = [
      loadSentence(plate(2, 13, 0, 6.5), measured(2), ana),
      loadSentence(plate(3, 8), noPace, ana),
      loadSentence(plate(0, 0, 0, 0), measured(2), ana),
      loadSentence(plate(5, 8, 2, 4), measured(2), ana),
    ];
    for (const sentence of branches) expect(sentence).not.toMatch(/\b(you|your|You|Your)\b/);
  });

  // The plate's blind spot has to follow the voice too, or one clause of a sentence
  // addresses the reader while the rest is about a colleague.
  it("possesses the unsized tickets to the subject", () => {
    expect(loadSentence(plate(5, 8, 2, 4), measured(2), ana)).toContain("2 of their 5 carry no estimate");
  });
});

describe("teamLoadSentence", () => {
  const pace = { perWorkingDay: 4, measuredCycles: 3 };

  it("weighs the team's plate against the pace it has been delivering", () => {
    const sentence = teamLoadSentence(plate(12, 40, 0, 10), pace, "Mobile");
    expect(sentence).toContain("Mobile is carrying 40 points across 12 tickets");
    expect(sentence).toContain("10 working days");
  });

  /**
   * The one branch that could not be a `Voice`.
   *
   * "Nothing open is assigned to Mobile" would be false: a team's plate counts the tickets
   * nobody is assigned at all, which is the whole difference between this read and a
   * person's.
   */
  it("does not claim a team's empty plate is unassigned", () => {
    const sentence = teamLoadSentence(plate(0, 0, 0, 0), pace, "Mobile");
    expect(sentence).toBe("Mobile has nothing open.");
    expect(sentence).not.toContain("assigned");
  });

  it("refuses to guess the days when the team has no measured pace", () => {
    const sentence = teamLoadSentence(plate(3, 8), { measuredCycles: 0 }, "Mobile");
    expect(sentence).toContain("8 points");
    expect(sentence).toContain("no pace");
    expect(sentence).not.toContain("working days");
  });
});

/**
 * The ticket's second guard-rail: the page says who else can read it.
 *
 * Asserted rather than eyeballed because the failure is silent — a page that says nothing
 * about its readers looks exactly like a page whose readers nobody computed.
 */
describe("readersSentence", () => {
  const admin = { id: "u1", displayName: "Instance owner" };
  const product = { id: "t1", name: "Product", key: "PRD" };

  it("names the instance admins and the teams whose administrators can read it", () => {
    const sentence = readersSentence({ instanceAdmins: [admin], teams: [product] }, null);
    expect(sentence).toBe("Readable by you, and by Instance owner and the administrators of Product.");
  });

  it("says so plainly when nobody else can read it, rather than going blank", () => {
    expect(readersSentence({ instanceAdmins: [], teams: [] }, null)).toBe(
      "Nobody else can read this page.",
    );
    expect(readersSentence({ instanceAdmins: [], teams: [] }, "Ana Ruiz")).toBe(
      "Nobody but Ana Ruiz can read this page.",
    );
  });

  it("names the subject rather than the reader on somebody else's page", () => {
    const sentence = readersSentence({ instanceAdmins: [admin], teams: [product] }, "Ana Ruiz");
    expect(sentence).toContain("Readable by Ana Ruiz");
    expect(sentence).not.toContain("by you");
  });

  it("joins three readers without an Oxford comma, as the rest of the app does", () => {
    const sentence = readersSentence(
      { instanceAdmins: [admin, { id: "u2", displayName: "Bo Chen" }], teams: [product] },
      null,
    );
    expect(sentence).toContain("Instance owner, Bo Chen and the administrators of Product");
  });
});
