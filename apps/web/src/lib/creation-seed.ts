import type { Project, Team } from "./api";
import type { Scope } from "@/store/ui";

/**
 * What a new entity inherits from wherever you were standing when you asked for it.
 *
 * Every value here is a *suggestion*: the dialogs pre-fill it and leave the field
 * editable. That is the whole difference between this and the defect it replaces —
 * the composer used to fall back to `teams[0]` invisibly, so a ticket could land in a
 * team nobody chose. These are on screen before anything is submitted.
 *
 * The team a scope resolves to is validated against the teams actually loaded. A scope
 * can point at a team that was archived a moment ago and is no longer on the list;
 * trusting it would file work into something invisible.
 */
export type CreationSeed = {
  ticket: { teamId: string; projectId: string; blocked: boolean };
  project: { teamId: string | undefined };
  team: { parentTeamId: string | undefined };
};

export function creationSeed(
  scope: Scope,
  teams: Pick<Team, "id" | "archived">[],
  projects: Pick<Project, "id" | "teamId" | "archived">[],
): CreationSeed {
  const scopeProject = scope.kind === "project" ? projects.find((p) => p.id === scope.id) : undefined;
  const wanted = scope.kind === "team" ? scope.id : scopeProject?.teamId;
  const teamId = wanted && teams.some((t) => t.id === wanted && !t.archived) ? wanted : "";

  const projectId =
    scopeProject && !scopeProject.archived && (!scopeProject.teamId || scopeProject.teamId === teamId)
      ? scopeProject.id
      : "";

  return {
    // A ticket's team is NOT NULL in the database, so an unresolvable team is not a
    // default to paper over — it is a question the person has to answer.
    ticket: { teamId, projectId, blocked: teamId === "" },
    project: { teamId: teamId || undefined },
    team: { parentTeamId: teamId || undefined },
  };
}
