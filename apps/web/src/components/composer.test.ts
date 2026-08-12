import { describe, expect, it } from "vitest";
import { isComposableTeam } from "./composer";

describe("isComposableTeam", () => {
  // All four combinations of the two reasons a team can be unofferable. Exactly one
  // is `true`: neither archived, nor refused by the server's own rule.
  const CASES: { archived: boolean; editable: boolean; expected: boolean }[] = [
    { archived: false, editable: true, expected: true },
    { archived: false, editable: false, expected: false },
    { archived: true, editable: true, expected: false },
    { archived: true, editable: false, expected: false },
  ];

  it("is true only for a team that is both active and editable", () => {
    for (const { archived, editable, expected } of CASES) {
      expect(
        isComposableTeam({ archived, editable }),
        `archived=${archived} editable=${editable}`,
      ).toBe(expected);
    }
  });
});
