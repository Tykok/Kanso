import { describe, expect, it } from "vitest";
import { composerEmptyReason, isComposableTeam } from "./composer";

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

describe("composerEmptyReason", () => {
  // Zero teams is the fresh-instance case and the every-team-archived case at once —
  // `api.teams(false)` already excludes archived rows, so both land on the same count.
  // Anything above zero means a team exists and the actor is the reason none of them
  // are composable.
  const CASES: { teamCount: number; expected: "no-teams" | "not-editable" }[] = [
    { teamCount: 0, expected: "no-teams" },
    { teamCount: 1, expected: "not-editable" },
    { teamCount: 5, expected: "not-editable" },
  ];

  it("separates 'nothing exists' from 'nothing will take yours'", () => {
    for (const { teamCount, expected } of CASES) {
      expect(composerEmptyReason(teamCount), `teamCount=${teamCount}`).toBe(expected);
    }
  });
});
