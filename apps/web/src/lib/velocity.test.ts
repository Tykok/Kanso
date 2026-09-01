import { describe, expect, it } from "vitest";
import type { EffectiveVelocity } from "./api";
import { formatRate, velocityCaption } from "./velocity";

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
    expect(caption.inForce).toContain("Declare one below");
    expect(caption.inForce).toContain("close a cycle");
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
