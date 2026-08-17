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
   * this asserts the shape a renderer relies on instead: eleven distinct kinds, none of
   * them empty, so a `switch` over them cannot be silently short a branch.
   */
  it("names eleven distinct activity kinds", () => {
    expect(new Set(ACTIVITY_KINDS).size).toBe(ACTIVITY_KINDS.length);
    expect(ACTIVITY_KINDS).toHaveLength(11);
    expect(ACTIVITY_KINDS.every((kind) => kind.length > 0)).toBe(true);
  });
});
