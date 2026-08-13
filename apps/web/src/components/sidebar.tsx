"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";
import { actionById, type ActionContext } from "@/lib/actions";
import { api, type Project, type Team } from "@/lib/api";
import { keys } from "@/lib/queries";
import { useUi, type Scope } from "@/store/ui";
import { menuItems } from "./menu-items";
import { Menu } from "./menu";
import { BrandMenu } from "./brand-menu";
import { GroupLabel } from "./ui/group-label";

type Row =
  | { kind: "team"; team: Team; depth: number }
  | { kind: "project"; project: Project; depth: number };

/** How far a nested team or project indents, capped at two levels — matches the
 *  `Math.min(depth, 2)` below, so there is never a fourth value to define. */
const DEPTH_PAD = ["pl-1.5", "pl-[18px]", "pl-[30px]"];

/**
 * The tree, flattened in reading order: a team, its projects, then its sub-teams.
 * Depth is capped at two indents, as before: deeper than that the labels lose more in
 * width than they gain in clarity.
 */
function tree(teams: Team[], projects: Project[]): Row[] {
  const known = new Set(teams.map((team) => team.id));

  const byParent = new Map<string | undefined, Team[]>();
  for (const team of teams) {
    // A parent missing from the list — archived while archived rows are hidden —
    // would hide the whole branch under it. The team is drawn at the root instead.
    const parent = team.parentTeamId && known.has(team.parentTeamId) ? team.parentTeamId : undefined;
    byParent.set(parent, [...(byParent.get(parent) ?? []), team]);
  }

  const byTeam = new Map<string, Project[]>();
  for (const project of projects) {
    if (!project.teamId || !known.has(project.teamId)) continue;
    byTeam.set(project.teamId, [...(byTeam.get(project.teamId) ?? []), project]);
  }

  const rows: Row[] = [];
  const walk = (parent: string | undefined, depth: number) => {
    const children = [...(byParent.get(parent) ?? [])].sort((a, b) => a.name.localeCompare(b.name));
    for (const team of children) {
      rows.push({ kind: "team", team, depth: Math.min(depth, 2) });
      const owned = [...(byTeam.get(team.id) ?? [])].sort((a, b) => a.name.localeCompare(b.name));
      for (const project of owned) {
        rows.push({ kind: "project", project, depth: Math.min(depth + 1, 2) });
      }
      walk(team.id, depth + 1);
    }
  };
  walk(undefined, 0);
  return rows;
}

/**
 * The projects with no team, plus those whose team is not on screen. Together with
 * `tree` above, every project therefore appears exactly once.
 */
function rootProjects(teams: Team[], projects: Project[]): Project[] {
  const known = new Set(teams.map((team) => team.id));
  return projects
    .filter((project) => !project.teamId || !known.has(project.teamId))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * A row's shared shape: `nav-item`/`nav-depth-N` carry no styling of their own any
 * more (the drawing below is Tailwind's), but they stay in the DOM as the hooks
 * `e2e/support.ts`'s `sidebarRow` and a dozen depth assertions already reach for —
 * moving the styling off them was this task's job, not renumbering every test that
 * names a depth.
 */
function navItemClass(depth: number, current: boolean) {
  return cn(
    "nav-item",
    `nav-depth-${depth}`,
    "group flex items-center gap-1 rounded-md pr-1.5",
    current ? "bg-accent-soft font-medium text-foreground" : "text-muted-foreground hover:bg-accent",
  );
}

function TeamRow({
  team,
  depth,
  current,
  ctx,
  onSelect,
}: {
  team: Team;
  depth: number;
  current: boolean;
  ctx: ActionContext;
  onSelect: () => void;
}) {
  const items = menuItems(ctx, [
    "project.create",
    "team.create",
    "team.rename",
    team.archived ? "team.unarchive" : "team.archive",
    "team.delete",
  ]);

  return (
    <div
      data-testid="nav-item"
      className={navItemClass(depth, current)}
      data-kind="team"
      data-current={current}
      data-archived={team.archived}
    >
      <button
        className={cn(
          "flex min-w-0 flex-1 items-center justify-between gap-2 py-[5px] text-left",
          DEPTH_PAD[depth],
          team.archived && "opacity-55",
        )}
        aria-current={current}
        onClick={onSelect}
        title={`${team.name} — prefix ${team.key}`}
      >
        <span className="truncate">{team.name}</span>
        {/*
          Hidden from the accessibility tree: without this the button would be called
          "Core KAN" and the prefix would enter every team's name. It is still
          announced, as a description, through the `title` above.
        */}
        <span aria-hidden="true" className="shrink-0 font-mono text-11 text-faint">
          {team.key}
        </span>
      </button>
      <Menu label={`Actions for ${team.name}`} items={items} />
    </div>
  );
}

function ProjectRow({
  project,
  depth,
  current,
  ctx,
  onSelect,
}: {
  project: Project;
  depth: number;
  current: boolean;
  ctx: ActionContext;
  onSelect: () => void;
}) {
  const items = menuItems(ctx, [
    "project.edit",
    project.archived ? "project.unarchive" : "project.archive",
    "project.delete",
  ]);

  return (
    <div
      data-testid="nav-item"
      className={navItemClass(depth, current)}
      data-kind="project"
      data-current={current}
      data-archived={project.archived}
    >
      <button
        className={cn(
          "flex min-w-0 flex-1 items-center gap-2 py-[5px] text-left before:size-1 before:shrink-0 before:rounded-full before:bg-faint",
          DEPTH_PAD[depth],
          project.archived && "opacity-55",
        )}
        aria-current={current}
        onClick={onSelect}
        title={project.name}
      >
        <span className="truncate">{project.name}</span>
      </button>
      <Menu label={`Actions for ${project.name}`} items={items} />
    </div>
  );
}

export function Sidebar({ ctx, syncSummary }: { ctx: ActionContext; syncSummary: string }) {
  const { scope, setScope, showArchived, setShowArchived } = useUi();

  /**
   * One query for every project, with no `teamId`: the sidebar needs the whole tree
   * to draw itself, and a query per team would mean N requests for a single render.
   */
  const teams = useQuery({
    queryKey: keys.teams(showArchived),
    queryFn: () => api.teams(showArchived),
  });
  const projects = useQuery({
    queryKey: keys.projects(showArchived),
    queryFn: () => api.projects({ includeArchived: showArchived }),
  });

  const teamList = useMemo(() => teams.data ?? [], [teams.data]);
  const projectList = useMemo(() => projects.data ?? [], [projects.data]);
  const rows = useMemo(() => tree(teamList, projectList), [teamList, projectList]);
  const loose = useMemo(() => rootProjects(teamList, projectList), [teamList, projectList]);

  const at = (next: Scope): ActionContext => ({ ...ctx, scope: next });

  /**
   * The two header `+` buttons create at the root, explicitly: a team with no parent,
   * a project with no team. They therefore get a context scoped to "all" rather than
   * the current selection, which would make the result depend on whatever happens to
   * be open.
   */
  const rootCtx = at({ kind: "all" });
  const newTeam = actionById("team.create");
  const newProject = actionById("project.create");

  const allCurrent = scope.kind === "all";

  return (
    <aside className="flex max-[720px]:hidden w-full flex-col gap-[22px] bg-card px-2.5 py-4 overflow-y-auto">
      <BrandMenu ctx={rootCtx} />

      <div>
        <GroupLabel className="pt-0">Views</GroupLabel>
        <div
          data-testid="nav-item"
          className={cn(
            "nav-item",
            "flex items-center gap-1 rounded-md pr-1.5",
            allCurrent ? "bg-accent-soft font-medium text-foreground" : "text-muted-foreground hover:bg-accent",
          )}
          data-kind="view"
          data-current={allCurrent}
        >
          <button
            className="min-w-0 flex-1 py-[5px] pl-1.5 text-left"
            aria-current={allCurrent}
            onClick={() => setScope({ kind: "all" })}
          >
            <span className="truncate">All tickets</span>
          </button>
        </div>
      </div>

      <div>
        <GroupLabel className="flex items-center gap-1.5">
          <span className="flex-1">Teams</span>
          {newTeam.when(rootCtx) && (
            <button
              className="nav-add flex size-5 items-center justify-center rounded normal-case tracking-normal text-faint hover:bg-accent hover:text-foreground"
              aria-label="New team"
              title={newTeam.label}
              onClick={() => newTeam.run(rootCtx)}
            >
              +
            </button>
          )}
        </GroupLabel>

        {rows.length === 0 && <div className="px-1.5 py-1 text-12 text-faint">No team yet</div>}

        {rows.map((row) =>
          row.kind === "team" ? (
            <TeamRow
              key={`team-${row.team.id}`}
              team={row.team}
              depth={row.depth}
              current={scope.kind === "team" && scope.id === row.team.id}
              ctx={at({ kind: "team", id: row.team.id })}
              onSelect={() => setScope({ kind: "team", id: row.team.id })}
            />
          ) : (
            <ProjectRow
              key={`project-${row.project.id}`}
              project={row.project}
              depth={row.depth}
              current={scope.kind === "project" && scope.id === row.project.id}
              ctx={at({ kind: "project", id: row.project.id })}
              onSelect={() => setScope({ kind: "project", id: row.project.id })}
            />
          ),
        )}
      </div>

      <div>
        <GroupLabel className="flex items-center gap-1.5">
          <span className="flex-1">Projects</span>
          {newProject.when(rootCtx) && (
            <button
              className="nav-add flex size-5 items-center justify-center rounded normal-case tracking-normal text-faint hover:bg-accent hover:text-foreground"
              aria-label="New project"
              title={newProject.label}
              onClick={() => newProject.run(rootCtx)}
            >
              +
            </button>
          )}
        </GroupLabel>

        {loose.length === 0 && <div className="px-1.5 py-1 text-12 text-faint">No project without a team</div>}

        {loose.map((project) => (
          <ProjectRow
            key={`root-${project.id}`}
            project={project}
            depth={0}
            current={scope.kind === "project" && scope.id === project.id}
            ctx={at({ kind: "project", id: project.id })}
            onSelect={() => setScope({ kind: "project", id: project.id })}
          />
        ))}
      </div>

      <div className="mt-auto flex flex-col gap-2 px-1.5">
        <button
          className={cn(
            "flex items-center gap-2 rounded-md py-[5px] text-left hover:bg-accent",
            showArchived ? "text-foreground" : "text-muted-foreground",
          )}
          aria-pressed={showArchived}
          onClick={() => setShowArchived(!showArchived)}
        >
          <span
            className={cn(
              "size-3 shrink-0 rounded-[3px] border border-border",
              showArchived && "border-primary bg-primary",
            )}
          />
          Show archived
        </button>
        <div className="text-11 text-faint">{syncSummary}</div>
      </div>
    </aside>
  );
}
