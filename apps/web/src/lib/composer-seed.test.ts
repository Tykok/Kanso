import { describe, expect, it } from "vitest";
import { composerSeed } from "./composer-seed";
import type { Project, Team } from "./api";

const core: Team = {
  id: "team-core",
  name: "Core",
  key: "KAN",
  archived: false,
  ticketCount: 1,
  mirror: { state: "synced" },
};

const legacy: Team = { ...core, id: "team-legacy", name: "Legacy", key: "LEG", archived: true };

const refonte: Project = {
  id: "project-refonte",
  name: "Refonte",
  status: "active",
  teamId: "team-core",
  archived: false,
  mirror: { state: "synced" },
};

const transverse: Project = { ...refonte, id: "project-transverse", teamId: undefined };
const orphaned: Project = { ...refonte, id: "project-orphaned", teamId: "team-legacy" };

/** What the composer is handed: `api.teams(false)` and `api.projects()`, both live-only. */
const live = { teams: [core], projects: [refonte, transverse] };

describe("composerSeed", () => {
  it("takes the scoped team when it is one of the fetched teams", () => {
    expect(composerSeed({ kind: "team", id: core.id }, live.teams, live.projects)).toEqual({
      teamId: core.id,
      projectId: "",
    });
  });

  it("resolves the team through the scoped project, and keeps the project", () => {
    expect(composerSeed({ kind: "project", id: refonte.id }, live.teams, live.projects)).toEqual({
      teamId: core.id,
      projectId: refonte.id,
    });
  });

  it("seeds nothing at all from the all-tickets view", () => {
    expect(composerSeed({ kind: "all" }, live.teams, live.projects)).toEqual({
      teamId: "",
      projectId: "",
    });
  });

  it("keeps a team-less project without inventing a team for it", () => {
    expect(composerSeed({ kind: "project", id: transverse.id }, live.teams, live.projects)).toEqual({
      teamId: "",
      projectId: transverse.id,
    });
  });

  /**
   * The defect: an admin archives the team you are scoped to. The fetched list no
   * longer holds it, the Team select renders blank, and a blind seed would still file
   * the ticket into it on Enter.
   */
  it("falls through to the blocked state when the scoped team is gone from the list", () => {
    expect(composerSeed({ kind: "team", id: "team-vanished" }, live.teams, live.projects)).toEqual({
      teamId: "",
      projectId: "",
    });
  });

  it("refuses a team present but archived, for the same reason", () => {
    expect(composerSeed({ kind: "team", id: legacy.id }, [core, legacy], live.projects)).toEqual({
      teamId: "",
      projectId: "",
    });
  });

  it("drops a project whose team did not survive the check", () => {
    expect(
      composerSeed({ kind: "project", id: orphaned.id }, [core, legacy], [...live.projects, orphaned]),
    ).toEqual({ teamId: "", projectId: "" });
  });

  it("seeds nothing from a scope naming something the lists do not hold", () => {
    expect(composerSeed({ kind: "project", id: "project-gone" }, live.teams, live.projects)).toEqual({
      teamId: "",
      projectId: "",
    });
  });
});
