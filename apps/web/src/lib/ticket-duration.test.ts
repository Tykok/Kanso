import { describe, expect, it } from "vitest";
import type { TicketDuration } from "./api";
import { durationCopy } from "./ticket-duration";

/** The three refusals carry nothing but the reason — the shape the API actually sends. */
type Absence = Exclude<TicketDuration, { basis: "estimated" }>;

const absent = (basis: Absence["basis"]): TicketDuration => ({ basis });

describe("what the ticket says about how long it will take", () => {
  it("writes a range, never a date", () => {
    const copy = durationCopy({
      basis: "estimated",
      lowWorkingDays: 3,
      highWorkingDays: 5,
      points: 8,
      assignees: 2,
      withoutVelocity: 0,
    });

    expect(copy.value).toBe("~3 to 5 working days");
    expect(copy.note).toBe("8 points at the 2 assignees' combined pace. An estimate, not a deadline.");
  });

  it("still reads as an estimate when the two ends meet", () => {
    const copy = durationCopy({
      basis: "estimated",
      lowWorkingDays: 1,
      highWorkingDays: 1,
      points: 1,
      assignees: 1,
      withoutVelocity: 0,
    });

    // The tilde is doing the work here: "1 working day" alone would be read as a promise.
    expect(copy.value).toBe("~1 working day");
  });

  it("says how much of the assignee list the range could not account for", () => {
    const copy = durationCopy({
      basis: "estimated",
      lowWorkingDays: 6,
      highWorkingDays: 10,
      points: 8,
      assignees: 2,
      withoutVelocity: 1,
    });

    expect(copy.note).toBe(
      "8 points, and 1 of 2 assignees has no velocity yet — the real range is shorter than this one.",
    );
  });

  it("agrees in number when more than one assignee is unmeasured", () => {
    const copy = durationCopy({
      basis: "estimated",
      lowWorkingDays: 6,
      highWorkingDays: 10,
      points: 8,
      assignees: 3,
      withoutVelocity: 2,
    });

    expect(copy.note).toContain("2 of 3 assignees have no velocity yet");
  });
});

describe("the three ways of saying nothing, each said differently", () => {
  it("names the missing size, and the gesture that fixes it", () => {
    const copy = durationCopy(absent("no_estimate"));

    expect(copy.value).toBeNull();
    expect(copy.note).toContain("Not sized yet");
    expect(copy.note).toContain("estimate");
  });

  it("names the missing assignee, and says the ticket is unowned rather than slow", () => {
    const copy = durationCopy(absent("no_assignee"));

    expect(copy.value).toBeNull();
    expect(copy.note).toContain("Nobody is on this yet");
    expect(copy.note).toContain("unowned ticket rather than a slow one");
  });

  it("names the missing velocity, and both ways of getting one", () => {
    const copy = durationCopy(absent("no_velocity"));

    expect(copy.value).toBeNull();
    expect(copy.note).toContain("Settings › Velocity");
    expect(copy.note).toContain("close a cycle");
  });

  it("gives three different sentences, so a reader can tell which fix is theirs", () => {
    const notes = (["no_estimate", "no_assignee", "no_velocity"] as const).map(
      (basis) => durationCopy(absent(basis)).note,
    );

    expect(new Set(notes).size).toBe(3);
    // And none of them is empty: a blank field is indistinguishable from a broken one.
    for (const note of notes) expect(note.length).toBeGreaterThan(0);
  });
});
