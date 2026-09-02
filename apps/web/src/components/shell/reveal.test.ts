import { describe, expect, it } from "vitest";
import { isRevealed, NOTHING_HELD, retractsOnLeave, sameHold } from "./reveal";

/** A 248px column down the left edge of a 1440×900 window. */
const PANEL = { right: 248 };
const VIEWPORT = { height: 900 };

describe("isRevealed", () => {
  it("is held by any one reason on its own", () => {
    expect(isRevealed(NOTHING_HELD)).toBe(false);
    expect(isRevealed({ ...NOTHING_HELD, pointer: true })).toBe(true);
    expect(isRevealed({ ...NOTHING_HELD, focus: true })).toBe(true);
    expect(isRevealed({ ...NOTHING_HELD, button: true })).toBe(true);
  });

  it("keeps the panel out when one of two reasons goes away", () => {
    // The case the three separate booleans exist for: entered with the pointer, then
    // tabbed into. The mouse wandering off must not take the focus with it, and a single
    // "opened by" field could only remember the last answer.
    const both = { pointer: true, focus: true, button: false };
    expect(isRevealed({ ...both, pointer: false })).toBe(true);
    expect(isRevealed({ ...both, focus: false })).toBe(true);
  });
});

describe("sameHold", () => {
  it("compares the three reasons and not the object", () => {
    expect(sameHold(NOTHING_HELD, { ...NOTHING_HELD })).toBe(true);
    expect(sameHold(NOTHING_HELD, { ...NOTHING_HELD, focus: true })).toBe(false);
  });
});

describe("retractsOnLeave", () => {
  it("retracts when the pointer turns to the content", () => {
    expect(retractsOnLeave({ x: 249, y: 400 }, PANEL, VIEWPORT)).toBe(true);
    expect(retractsOnLeave({ x: 1200, y: 12 }, PANEL, VIEWPORT)).toBe(true);
  });

  it("retracts the moment the pointer crosses the edge, not a pixel later", () => {
    expect(retractsOnLeave({ x: 248, y: 400 }, PANEL, VIEWPORT)).toBe(true);
    expect(retractsOnLeave({ x: 247, y: 400 }, PANEL, VIEWPORT)).toBe(false);
  });

  it("holds when the pointer reaches past the window", () => {
    // Up to the address bar, down to the dock, left to the desktop. The reader has not
    // looked away from the column, and snapping shut here is the flicker: the pointer
    // comes back through the same edge a moment later and the panel opens again.
    expect(retractsOnLeave({ x: 120, y: 0 }, PANEL, VIEWPORT)).toBe(false);
    expect(retractsOnLeave({ x: 120, y: -4 }, PANEL, VIEWPORT)).toBe(false);
    expect(retractsOnLeave({ x: 120, y: 900 }, PANEL, VIEWPORT)).toBe(false);
    expect(retractsOnLeave({ x: 0, y: 400 }, PANEL, VIEWPORT)).toBe(false);
    expect(retractsOnLeave({ x: -8, y: 400 }, PANEL, VIEWPORT)).toBe(false);
  });

  it("holds while the pointer is still over the panel's own footprint", () => {
    // A `mouseleave` from in here is the pointer crossing onto a popover the column
    // opened — the brand menu under the seal — which is portalled to the body and so is
    // not a descendant of the panel. Retracting would unmount the trigger under it.
    expect(retractsOnLeave({ x: 60, y: 120 }, PANEL, VIEWPORT)).toBe(false);
  });

  it("reads the panel's edge rather than assuming 248", () => {
    // The mobile drawer is 288px and the reveal could be re-sized with it; the rule is
    // about the edge the pointer crossed, not about a number written twice.
    expect(retractsOnLeave({ x: 260, y: 400 }, { right: 288 }, VIEWPORT)).toBe(false);
    expect(retractsOnLeave({ x: 300, y: 400 }, { right: 288 }, VIEWPORT)).toBe(true);
  });
});
