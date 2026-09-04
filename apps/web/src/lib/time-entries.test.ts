import { describe, expect, it } from "vitest";
import type { TicketTime, TimeEntry } from "@/lib/api";
import { byDay, elapsedSince, parseWorkedTime, totalSentence, workedTime } from "./time-entries";

/**
 * The fixtures **omit** absent keys rather than setting them to null, mirroring what Jackson
 * actually sends — the policy `insights.test.ts` states and the reason the tokens screen once
 * shipped `Last used Invalid Date`. A settled entry has no `startedAt` unless it was clocked;
 * a running one has no `minutes` at all, and a builder that wrote `minutes: null` would test a
 * shape the server never sends.
 */
const settled = (minutes: number, spentOn = "2026-09-03", extra: Partial<TimeEntry> = {}): TimeEntry => ({
  id: `settled-${minutes}-${spentOn}`,
  ticketId: "t1",
  userId: "u1",
  spentOn,
  minutes,
  elapsedMinutes: minutes,
  createdAt: "2026-09-03T10:00:00Z",
  updatedAt: "2026-09-03T10:00:00Z",
  ...extra,
});

/** The shape the wire sends for a clock that is still going: no `minutes` key. */
const running = (startedAt: string, spentOn = "2026-09-03", userId = "u1"): TimeEntry => ({
  id: `running-${startedAt}`,
  ticketId: "t1",
  userId,
  spentOn,
  startedAt,
  elapsedMinutes: 7,
  createdAt: startedAt,
  updatedAt: startedAt,
});

const time = (entries: TimeEntry[], runningId?: string): TicketTime => ({
  totalMinutes: entries.reduce((total, entry) => total + (entry.minutes ?? 0), 0),
  entries,
  ...(runningId ? { runningId } : {}),
});

describe("workedTime", () => {
  it("says minutes under the hour, because nobody bills 0.75 h", () => {
    expect(workedTime(45)).toBe("45m");
    expect(workedTime(1)).toBe("1m");
  });

  it("drops the empty half of the pair rather than printing 2h 0m", () => {
    expect(workedTime(60)).toBe("1h");
    expect(workedTime(120)).toBe("2h");
    expect(workedTime(90)).toBe("1h 30m");
  });

  it("says 0m for a stopwatch pressed twice, which is a row somebody can then delete", () => {
    expect(workedTime(0)).toBe("0m");
    // Unreachable through the product; a clock skew can produce it and a `SUM` must not
    // print `-5m` as though it were a duration.
    expect(workedTime(-5)).toBe("0m");
  });

  /**
   * The reason this module exists at all. `duration()` in `lib/insights.ts` would answer
   * `2.1 days` here, correctly for an elapsed cycle time and wrongly for worked hours —
   * nobody works round the clock, and a reader would divide by a working day this product
   * refuses to guess at.
   */
  it("stays in hours past a day, and never converts worked hours to days", () => {
    expect(workedTime(1440)).toBe("24h");
    expect(workedTime(1500)).toBe("25h");
    expect(workedTime(7530)).toBe("125h 30m");
    for (const minutes of [1440, 1500, 7530, 44640]) {
      expect(workedTime(minutes)).not.toContain("day");
    }
  });

  it("never emits undefined or NaN, whatever it is handed", () => {
    for (const minutes of [0, 1, 59, 60, 61, 1439, 1440, 44640]) {
      expect(workedTime(minutes)).not.toMatch(/undefined|NaN/);
    }
  });
});

describe("elapsedSince", () => {
  it("reads the clock from its start, rounding as the server does", () => {
    expect(elapsedSince("2026-09-03T10:00:00Z", new Date("2026-09-03T10:45:00Z"))).toBe(45);
    // 40 seconds is a minute of somebody's day, not nought.
    expect(elapsedSince("2026-09-03T10:00:00Z", new Date("2026-09-03T10:00:40Z"))).toBe(1);
    expect(elapsedSince("2026-09-03T10:00:00Z", new Date("2026-09-04T12:30:00Z"))).toBe(1590);
  });

  it("floors at zero rather than counting backwards off a skewed clock", () => {
    expect(elapsedSince("2026-09-03T10:00:00Z", new Date("2026-09-03T09:00:00Z"))).toBe(0);
  });

  it("answers zero for a timestamp it cannot read, instead of NaN on the screen", () => {
    expect(elapsedSince("not a date", new Date("2026-09-03T10:00:00Z"))).toBe(0);
  });
});

describe("totalSentence", () => {
  it("says nothing logged rather than 0h, which reads as a failed load", () => {
    expect(totalSentence(time([]))).toBe("Nothing logged yet.");
  });

  it("counts the settled entries the total actually stands on", () => {
    expect(totalSentence(time([settled(90), settled(30)]))).toBe("2h logged over 2 entries.");
    expect(totalSentence(time([settled(45)]))).toBe("45m logged over 1 entry.");
  });

  /**
   * The case a `totalMinutes === 0` test would get wrong: one running clock and nothing
   * else is a total of nought and emphatically not an un-logged ticket.
   */
  it("does not call a ticket un-logged when its only row is a running clock", () => {
    const only = time([running("2026-09-03T10:00:00Z")], "running-2026-09-03T10:00:00Z");
    expect(totalSentence(only, 12)).toBe(
      "Nothing settled yet, and your timer has been running 12m.",
    );
  });

  it("names the running clock beside the total and never inside it", () => {
    const both = time([settled(120), running("2026-09-03T14:00:00Z")], "running-2026-09-03T14:00:00Z");
    expect(totalSentence(both, 25)).toBe(
      "2h logged over 1 entry, and your timer has been running 25m.",
    );
    // The total is the settled rows only — the clock's 25 minutes are not in it.
    expect(both.totalMinutes).toBe(120);
  });

  it("says somebody else's clock is running without claiming it is yours", () => {
    const theirs = time([settled(60), running("2026-09-03T14:00:00Z", "2026-09-03", "u2")]);
    expect(totalSentence(theirs)).toBe("1h logged over 1 entry, and another clock is running.");
  });

  it("never emits undefined or NaN, on any of the four shapes", () => {
    const shapes: [TicketTime, number | undefined][] = [
      [time([]), undefined],
      [time([settled(30)]), undefined],
      [time([running("2026-09-03T10:00:00Z")]), 5],
      [time([settled(30), running("2026-09-03T10:00:00Z")]), 5],
    ];
    for (const [value, minutes] of shapes) {
      expect(totalSentence(value, minutes)).not.toMatch(/undefined|NaN/);
    }
  });
});

describe("byDay", () => {
  it("groups on spentOn, newest day first", () => {
    const days = byDay([settled(30, "2026-09-01"), settled(60, "2026-09-03"), settled(15, "2026-09-01")]);
    expect(days.map((day) => day.spentOn)).toEqual(["2026-09-03", "2026-09-01"]);
    expect(days.map((day) => day.minutes)).toEqual([60, 45]);
  });

  /**
   * `createdAt` is when the entry was typed. Every row here was typed on the 3rd and two of
   * them are Monday's work, so grouping on it would put three days into one.
   */
  it("files an entry under the day worked, not the day it was typed", () => {
    const typedOnThursday = { createdAt: "2026-09-03T09:00:00Z", updatedAt: "2026-09-03T09:00:00Z" };
    const days = byDay([
      settled(60, "2026-08-31", typedOnThursday),
      settled(30, "2026-09-01", typedOnThursday),
    ]);
    expect(days.map((day) => day.spentOn)).toEqual(["2026-09-01", "2026-08-31"]);
  });

  it("keeps a running clock in its day's rows while leaving it out of the day's total", () => {
    const days = byDay([settled(60, "2026-09-03"), running("2026-09-03T14:00:00Z", "2026-09-03")]);
    expect(days).toHaveLength(1);
    expect(days[0].entries).toHaveLength(2);
    expect(days[0].minutes).toBe(60);
  });

  it("is empty for no rows rather than one empty day", () => {
    expect(byDay([])).toEqual([]);
  });
});

describe("parseWorkedTime", () => {
  it("reads the three shapes people actually type", () => {
    expect(parseWorkedTime("3h 30m")).toBe(210);
    expect(parseWorkedTime("2:30")).toBe(150);
    expect(parseWorkedTime("150")).toBe(150);
    expect(parseWorkedTime("45m")).toBe(45);
    expect(parseWorkedTime("2h")).toBe(120);
    expect(parseWorkedTime("90min")).toBe(90);
    expect(parseWorkedTime(" 1H15M ")).toBe(75);
  });

  /**
   * The refusal that matters most. `2.5` meaning two and a half minutes is the bug this
   * function exists to make impossible, and a null the caller must show is how it does it —
   * a guess of 2 would put a wrong duration on an invoice with nothing on screen to say so.
   */
  it("refuses what it cannot read rather than guessing a number", () => {
    for (const raw of ["", "  ", "2.5", "2.5h", "about an hour", "abc", "-30", "1:75", "3h 30"]) {
      expect(parseWorkedTime(raw)).toBeNull();
    }
  });
});
