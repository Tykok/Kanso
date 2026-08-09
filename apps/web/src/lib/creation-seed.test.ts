import { describe, expect, it } from "vitest";
import { creationSeed } from "./creation-seed";

const core = { id: "team-core", archived: false };
const legacy = { id: "team-legacy", archived: true };
const refonte = { id: "proj-refonte", teamId: "team-core", archived: false };
const transverse = { id: "proj-transverse", teamId: undefined, archived: false };
const orphaned = { id: "proj-orphaned", teamId: "team-legacy", archived: false };

const teams = [core, legacy];
const projects = [refonte, transverse, orphaned];
const seed = (scope: Parameters<typeof creationSeed>[0]) =>
  creationSeed(scope, teams, projects);

describe("from the all-tickets scope", () => {
  it("blocks a ticket, because a ticket cannot exist without a team", () => {
    expect(seed({ kind: "all" }).ticket).toEqual({ teamId: "", projectId: "", blocked: true });
  });

  it("creates a project with no team and a team at the root", () => {
    expect(seed({ kind: "all" }).project.teamId).toBeUndefined();
    expect(seed({ kind: "all" }).team.parentTeamId).toBeUndefined();
  });
});

describe("from a team scope", () => {
  const scope = { kind: "team", id: core.id } as const;

  it("puts the ticket and the project in that team", () => {
    expect(seed(scope).ticket).toEqual({ teamId: core.id, projectId: "", blocked: false });
    expect(seed(scope).project.teamId).toBe(core.id);
  });

  it("makes a new team a sub-team of it", () => {
    expect(seed(scope).team.parentTeamId).toBe(core.id);
  });

  it("blocks the ticket when that team is archived and off the list", () => {
    expect(seed({ kind: "team", id: legacy.id }).ticket.blocked).toBe(true);
    expect(seed({ kind: "team", id: legacy.id }).team.parentTeamId).toBeUndefined();
  });

  /**
   * Not the same case as the archived one above, and the difference is the one that
   * mattered: an admin deletes the team you are scoped to in another browser, minutes
   * apart. The id survives in the store and matches nothing at all in the fetched
   * list — there is no `archived` flag to consult. Seeded blind, the Team select
   * renders empty while `teamId` still holds the dead id.
   */
  it("infers nothing from a team the lists do not hold at all", () => {
    const s = seed({ kind: "team", id: "team-vanished" });
    expect(s.ticket).toEqual({ teamId: "", projectId: "", blocked: true });
    expect(s.project.teamId).toBeUndefined();
    expect(s.team.parentTeamId).toBeUndefined();
  });
});

describe("from a project scope", () => {
  it("reads one level up: the project's team carries the ticket and the new team", () => {
    const s = seed({ kind: "project", id: refonte.id });
    expect(s.ticket).toEqual({ teamId: core.id, projectId: refonte.id, blocked: false });
    expect(s.project.teamId).toBe(core.id);
    expect(s.team.parentTeamId).toBe(core.id);
  });

  it("blocks the ticket from a team-less project, and infers no parent", () => {
    const s = seed({ kind: "project", id: transverse.id });
    expect(s.ticket).toEqual({ teamId: "", projectId: transverse.id, blocked: true });
    expect(s.project.teamId).toBeUndefined();
    expect(s.team.parentTeamId).toBeUndefined();
  });

  /**
   * The whole object, not just `blocked`: the project has to be dropped too. A
   * project whose team did not resolve is no longer a legal home for a ticket, and an
   * assertion narrow enough to ignore `projectId` would pass while the composer
   * pre-filled a project the chosen team does not own.
   */
  it("infers nothing from a project whose team is archived and off the list", () => {
    const s = seed({ kind: "project", id: orphaned.id });
    expect(s.ticket).toEqual({ teamId: "", projectId: "", blocked: true });
    expect(s.project.teamId).toBeUndefined();
    expect(s.team.parentTeamId).toBeUndefined();
  });

  it("seeds nothing from a scope naming a project the lists do not hold", () => {
    const s = seed({ kind: "project", id: "proj-gone" });
    expect(s.ticket).toEqual({ teamId: "", projectId: "", blocked: true });
    expect(s.project.teamId).toBeUndefined();
    expect(s.team.parentTeamId).toBeUndefined();
  });
});
