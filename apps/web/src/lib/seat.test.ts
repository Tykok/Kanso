import { describe, expect, it } from "vitest";
import type { InstanceRole } from "./api";
import { canConfigure, mayWrite } from "./seat";

/**
 * Both predicates over every role there is, plus the one that is not a role.
 *
 * A table rather than four `it`s, because the interesting property is the *shape* of the
 * two columns and not any single cell: `canConfigure` is true for two roles and
 * `mayWrite` for three, and the row that has to differ between them is `member`. A
 * regression that collapsed one into the other would pass any test written a role at a
 * time.
 */
const CASES: { role: InstanceRole | undefined; configure: boolean; write: boolean }[] = [
  { role: "owner", configure: true, write: true },
  { role: "admin", configure: true, write: true },
  { role: "member", configure: false, write: true },
  { role: "viewer", configure: false, write: false },
  // While `/api/me` is in flight. The two defaults deliberately fall in opposite
  // directions: an unknown seat gets no admin controls (showing them and taking them away
  // is the flash `canConfigureMembers` was written to stop) but does get the app drawn,
  // because blanking the composer on every cold load to protect a seat almost nobody
  // holds is the worse trade — and the server refuses the write either way.
  { role: undefined, configure: false, write: true },
];

describe("seat", () => {
  it("says who configures and who writes, for every role and for none", () => {
    for (const { role, configure, write } of CASES) {
      expect(canConfigure(role), `canConfigure(${role})`).toBe(configure);
      expect(mayWrite(role), `mayWrite(${role})`).toBe(write);
    }
  });

  it("covers every role the wire can carry", () => {
    // Not a formality: this file is the client's copy of a vocabulary the server owns, and
    // the failure mode is a role added to `InstanceRole` that nobody classified here — it
    // would fall through to whichever branch these functions happen to end on. The literal
    // is written out so adding a role forces this line to be read.
    const named = CASES.map((row) => row.role).filter((role) => role !== undefined);
    expect(new Set(named)).toEqual(new Set<InstanceRole>(["owner", "admin", "member", "viewer"]));
  });
});
