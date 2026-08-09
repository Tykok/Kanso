import type { Scope } from "@/store/ui";
import type { Project, Team } from "./api";

/**
 * What the composer's context bar starts on, given the current scope.
 *
 * The scope is a stored id, not a row: nothing keeps it in step with the lists the
 * composer was just handed. An admin archiving the team you are scoped to is enough,
 * and it is not a race — the two happen minutes apart, in two browsers. Seeded blind,
 * the Team select renders blank (its value matches no option) while `teamId` still
 * holds the archived id, and Enter files the ticket into a team nobody can see. That
 * is the exact defect the composer was built to remove, in a different disguise.
 *
 * So the seed is validated against what was actually fetched, and falls through to
 * the empty string when it does not survive — which is the blocked state the composer
 * already knows how to show. The project follows the team: a project of a team that
 * was not resolved is no longer a legal home, while a team-less one always is.
 *
 * Pure, and in a module of its own, so this is a unit test rather than a claim.
 */
export function composerSeed(
  scope: Scope,
  teams: Pick<Team, "id" | "archived">[],
  projects: Pick<Project, "id" | "teamId" | "archived">[],
): { teamId: string; projectId: string } {
  const scopeProject = scope.kind === "project" ? projects.find((row) => row.id === scope.id) : undefined;
  const wanted = scope.kind === "team" ? scope.id : (scopeProject?.teamId ?? "");
  const live = teams.some((team) => team.id === wanted && !team.archived);
  const teamId = wanted && live ? wanted : "";

  const projectId =
    scopeProject && !scopeProject.archived && (!scopeProject.teamId || scopeProject.teamId === teamId)
      ? scopeProject.id
      : "";

  return { teamId, projectId };
}
