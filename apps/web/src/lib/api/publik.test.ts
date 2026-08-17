import { describe, expect, it } from "vitest";
import { splitKey } from "./publik";

describe("splitKey", () => {
  it("splits a ticket identifier into the two path segments the API wants", () => {
    expect(splitKey("KAN-142")).toEqual({ teamKey: "KAN", number: "142" });
  });

  it("cuts at the last dash, so a hyphenated team key still resolves", () => {
    // No team key contains a dash today (`@Size(min = 2, max = 8)` and nothing else),
    // which is exactly why cutting at the first one would look correct for years and
    // then quietly send half a key the day somebody names a team `WEB-UI`.
    expect(splitKey("WEB-UI-7")).toEqual({ teamKey: "WEB-UI", number: "7" });
  });

  it("does not invent a number for a key that has none", () => {
    // A key typed into the address bar by hand. The server answers 404 for it, which is
    // the same answer it gives for a ticket that is not published — and the page says
    // so rather than crashing on a parse.
    expect(splitKey("KAN")).toEqual({ teamKey: "KAN", number: "" });
  });
});
