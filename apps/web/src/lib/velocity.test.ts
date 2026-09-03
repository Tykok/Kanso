import { describe, expect, it } from "vitest";
import type { EffectiveVelocity } from "./api";
import { formatRate, velocityCaption } from "./velocity";
import { about } from "./voice";

// What the API actually sends when there is nothing: the nulls are omitted, not null.
const base: EffectiveVelocity = { source: "none", measuredCycles: 0, cyclesUntilMeasured: 2 };

describe("the sentence saying which velocity a date came from", () => {
  it("names the declared one as in force, and says how much longer", () => {
    const caption = velocityCaption({
      ...base,
      perWorkingDay: 2,
      source: "declared",
      declared: 2,
      measured: 1,
      measuredCycles: 1,
      cyclesUntilMeasured: 1,
    });

    // The number in force is named, not just its origin: the input above this caption
    // always holds the declared value, so a caption that named only the source would leave
    // the one visible number being the one not in use.
    expect(caption.inForce).toBe(
      "Kanso is planning with the 2 points per working day you declared — 1 more closed cycle" +
        " and it will use the measured one.",
    );
    // The losing number survives the arbitration, which is what makes the gap visible.
    expect(caption.reference).toBe("Measured so far: 1, over 1 closed cycle.");
  });

  it("pluralises the wait, because 'not yet' without 'how much longer' is unactionable", () => {
    const caption = velocityCaption({
      ...base,
      perWorkingDay: 2,
      source: "declared",
      declared: 2,
      cyclesUntilMeasured: 2,
    });

    expect(caption.inForce).toContain("2 more closed cycles");
    expect(caption.reference).toBeNull();
  });

  it("names the measured one once it is in force, and still shows what was declared", () => {
    const caption = velocityCaption({
      ...base,
      perWorkingDay: 0.8,
      source: "measured",
      declared: 2,
      measured: 0.8,
      measuredCycles: 3,
      cyclesUntilMeasured: 0,
    });

    expect(caption.inForce).toBe(
      "Kanso is planning with 0.8 points per working day, measured from your last 3 closed cycles.",
    );
    expect(caption.reference).toBe(
      "You declared 2. Kanso keeps that as you wrote it — a lasting gap is worth knowing about, not correcting.",
    );
  });

  it("admits when the measurement stands on a single cycle", () => {
    const caption = velocityCaption({
      ...base,
      perWorkingDay: 1,
      source: "measured",
      measured: 1,
      measuredCycles: 1,
      cyclesUntilMeasured: 1,
    });

    expect(caption.inForce).toBe(
      "Kanso is planning with 1 point per working day, measured from your last closed cycle." +
        " One cycle is thin — it will settle as more close.",
    );
    expect(caption.reference).toBeNull();
  });

  it("explains the absence rather than rendering an empty caption", () => {
    const caption = velocityCaption(base);

    expect(caption.inForce).toContain("No velocity yet");
    // The two ways out are both named: an empty field with no exit reads as a bug.
    expect(caption.inForce).toContain("Declare one in your preferences");
    expect(caption.inForce).toContain("close a cycle");
    // Not "below". This caption is drawn on the progress page too, which has no field on
    // it, and a sentence that points at a control that is not there is worse than one that
    // names where the control lives.
    expect(caption.inForce).not.toContain("below");
  });

  it("names the number in force in every state that has one", () => {
    const declared = velocityCaption({
      ...base,
      perWorkingDay: 2,
      source: "declared",
      declared: 2,
      cyclesUntilMeasured: 1,
    });
    const measured = velocityCaption({
      ...base,
      perWorkingDay: 0.8,
      source: "measured",
      measured: 0.8,
      measuredCycles: 3,
      cyclesUntilMeasured: 0,
    });

    expect(declared.inForce).toContain("2 points per working day");
    expect(measured.inForce).toContain("0.8 points per working day");
  });

  it("always says something, whatever the source", () => {
    const sources = [
      base,
      { ...base, perWorkingDay: 1, source: "declared" as const, declared: 1, cyclesUntilMeasured: 2 },
      { ...base, perWorkingDay: 1, source: "measured" as const, measured: 1, measuredCycles: 2 },
    ];

    for (const velocity of sources) expect(velocityCaption(velocity).inForce.length).toBeGreaterThan(0);
  });

  /**
   * Screen 41 aims this caption at somebody who is not the reader.
   *
   * Found on screen and not by a suite: the page rendered "measured from *your* last 2
   * closed cycles" under a heading reading `subject's pace`, which is a sentence about the
   * wrong person and exactly the kind of thing only looking at the app catches. These
   * assertions are what stop it coming back — a blunt sweep for the second person over
   * every branch, rather than one example.
   */
  it("says nothing in the second person when the subject is somebody else", () => {
    const voice = about("Ana Ruiz");
    const sources = [
      base,
      { ...base, perWorkingDay: 2, source: "declared" as const, declared: 2, measured: 1, cyclesUntilMeasured: 1 },
      { ...base, perWorkingDay: 1, source: "measured" as const, measured: 1, measuredCycles: 1, declared: 2 },
      { ...base, perWorkingDay: 1, source: "measured" as const, measured: 1, measuredCycles: 3, declared: 2 },
    ];

    for (const velocity of sources) {
      const caption = velocityCaption(velocity, voice);
      for (const sentence of [caption.inForce, caption.reference ?? ""]) {
        // `you`, `your` and `You` as whole words. A substring test would trip on nothing
        // here, but `yours` and `your` are one letter apart and the next branch to be added
        // is the one that gets it wrong.
        expect(sentence).not.toMatch(/\b(you|your|You|Your)\b/);
      }
    }
  });

  it("keeps the instruction for the subject and drops it for everybody else", () => {
    // "Declare one in your preferences" is advice the reader can act on only on their own
    // page. Aimed at a colleague it would point them at the wrong person's settings.
    expect(velocityCaption(base).inForce).toContain("Declare one in your preferences");
    const theirs = velocityCaption(base, about("Ana Ruiz")).inForce;
    expect(theirs).toContain("Ana Ruiz");
    expect(theirs).not.toContain("Declare one in");
    expect(theirs).toContain("closed cycle with finished, sized work");
  });

  it("names the colleague where the second person named the reader", () => {
    const measured = velocityCaption(
      { ...base, perWorkingDay: 1, source: "measured", measured: 1, measuredCycles: 2, declared: 2 },
      about("Ana Ruiz"),
    );
    expect(measured.inForce).toContain("measured from their last 2 closed cycles");
    expect(measured.reference).toContain("Ana Ruiz declared 2");
    expect(measured.reference).toContain("as they wrote it");
  });
});

describe("how a rate is written down", () => {
  it("drops the decimals a rate does not have", () => {
    expect(formatRate(1)).toBe("1");
    expect(formatRate(0.8)).toBe("0.8");
  });

  it("stops at two, because a mean of three cycles cannot support more", () => {
    expect(formatRate(4 / 3)).toBe("1.33");
    expect(formatRate(2 / 3)).toBe("0.67");
  });
});
