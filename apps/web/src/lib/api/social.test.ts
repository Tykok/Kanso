import { describe, expect, it } from "vitest";
import { DEFAULT_PREFERENCES } from "./core";
import { ACTIVITY_KINDS } from "./social";

describe("the shared client", () => {
  /**
   * Screen 02 says the setting lives in the preferences; slice 0 is what made that true.
   * The default is the panel because that is what the app did before the setting existed —
   * a preference that changes behaviour for everyone who never set it is not a preference.
   */
  it("defaults openTicket to the panel", () => {
    expect(DEFAULT_PREFERENCES.openTicket).toBe("panel");
  });

  /**
   * The list is duplicated from the `activity_kind_chk` CHECK, which is the only place it
   * is enforced. Nothing can assert the two agree from here — Vitest has no database — so
   * this asserts the shape a renderer relies on instead: thirteen distinct kinds, none of
   * them empty, so a `switch` over them cannot be silently short a branch.
   *
   * The count is a tripwire, so it moves whenever a kind does — `pull_request_linked`,
   * from `V36`, is the fifteenth. Two branches adding a kind at once will both land here;
   * the resolution is the higher number, matching the union of their `activity_kind_chk`
   * lists.
   * The count is meant to be edited, and only alongside a migration: it is what makes a
   * kind added to the server and forgotten here fail out loud, rather than reaching the
   * feed as a wire value the renderer's exhaustive `switch` has no branch for.
   *
   * The name is the number now, because it was not: this said "thirteen" while asserting
   * fourteen, which is a tripwire whose label had already stopped being checked by
   * anything.
   */
  it("names fifteen distinct activity kinds", () => {
    expect(new Set(ACTIVITY_KINDS).size).toBe(ACTIVITY_KINDS.length);
    expect(ACTIVITY_KINDS).toHaveLength(15);
    expect(ACTIVITY_KINDS.every((kind) => kind.length > 0)).toBe(true);
  });
});
