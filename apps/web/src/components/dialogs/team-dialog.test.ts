import { describe, expect, it } from "vitest";
import type { InstanceRole } from "@/lib/api";
import { canConfigureMembers } from "./team-dialog";

describe("canConfigureMembers", () => {
  // `undefined` is the case that mattered: the mount condition this replaced,
  // `instanceRole !== "member"`, was true for it, which flashed a plain member the
  // full add/remove surface for as long as `/api/me` took to resolve. It must land
  // on `false` here, same as a genuine member — no controls until the role is known.
  const CASES: { role: InstanceRole | undefined; expected: boolean }[] = [
    { role: undefined, expected: false },
    { role: "member", expected: false },
    { role: "admin", expected: true },
    { role: "owner", expected: true },
  ];

  it("grants the roster's controls to owner and admin only, defaulting to none while loading", () => {
    for (const { role, expected } of CASES) {
      expect(canConfigureMembers(role), `role=${role}`).toBe(expected);
    }
  });
});
