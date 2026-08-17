import { describe, expect, it } from "vitest";
import { deliveredOn } from "./delivered";
import { parseVoted, serialiseVoted } from "./voted";

const now = new Date("2026-08-17T12:00:00Z");

describe("deliveredOn", () => {
  it("prints the day and the month, as the drawing does", () => {
    expect(deliveredOn("2026-07-18T09:14:00Z", now)).toBe("18 July");
  });

  it("adds the year once the release is not from this one", () => {
    expect(deliveredOn("2025-06-10T09:14:00Z", now)).toBe("10 June 2025");
  });

  it("reads the date in UTC, so it is the same date for every reader", () => {
    // 23:30 UTC on the 17th is the 18th in Tokyo. Converting would give two different
    // shipping dates for one release depending on who opened the page.
    expect(deliveredOn("2026-08-17T23:30:00Z", now)).toBe("17 August");
  });

  it("says nothing rather than 'Invalid Date' for a value it cannot read", () => {
    expect(deliveredOn("not a date", now)).toBe("");
  });
});

describe("remembered votes", () => {
  it("round-trips the set it wrote", () => {
    expect(parseVoted(serialiseVoted(new Set(["KAN-1", "KAN-2"])))).toEqual(
      new Set(["KAN-1", "KAN-2"]),
    );
  });

  it("treats an empty slot as nobody having voted", () => {
    expect(parseVoted(null)).toEqual(new Set());
  });

  it("survives whatever else is in the slot", () => {
    // localStorage is shared with every other script on the origin, and a page with no
    // session has no business throwing over a key it did not write.
    expect(parseVoted("{oh no")).toEqual(new Set());
    expect(parseVoted('{"votes":3}')).toEqual(new Set());
    expect(parseVoted('["KAN-1", 7, null]')).toEqual(new Set(["KAN-1"]));
  });
});
