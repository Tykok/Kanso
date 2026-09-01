import { describe, expect, it } from "vitest";
import { NO_TEAM_LABEL, hasNoTeam } from "./pills";

/**
 * The visible half of "a ticket can live without a team".
 *
 * `TicketIdentifier` itself is JSX and this suite runs under `environment: "node"`, so
 * what is pinned here is the decision it makes — which of its two branches a row takes —
 * rather than the markup it renders. The branch is the part with a rule behind it.
 */
describe("hasNoTeam", () => {
  it("is true exactly when no team has claimed the ticket", () => {
    expect(hasNoTeam({ teamId: undefined })).toBe(true);
    expect(hasNoTeam({ teamId: "3f2a" })).toBe(false);
  });

  /**
   * The shape that actually arrives. The API omits a null field rather than sending it,
   * so a draft reaches the client with no `teamId` key at all — and a `=== null` test,
   * which is what this looked like first, answered false for every one of them and drew
   * the identifier slot empty instead of badged.
   */
  it("answers for an absent key as well as an explicit null", () => {
    expect(hasNoTeam({})).toBe(true);
    expect(hasNoTeam({ teamId: null })).toBe(true);
  });

  /**
   * Reads the team and not the identifier, though the server sends the two null together.
   * The team is the fact; the missing identifier is its consequence. A row that somehow
   * arrived with one and not the other should still be drawn by what is actually absent.
   */
  it("asks about the team, not about the name the team would have given it", () => {
    expect(hasNoTeam({ teamId: "3f2a" })).toBe(false);
  });

  it("names the badge once, so a test and a screen cannot disagree about the words", () => {
    expect(NO_TEAM_LABEL).toBe("No team");
  });
});
