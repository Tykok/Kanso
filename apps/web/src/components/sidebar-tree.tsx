"use client";

import { cn } from "@/lib/utils";
import { menuItems } from "./menu-items";
import { Menu } from "./menu";
import { rowActionsTriggerClass } from "./ui/row";
import type { ActionContext } from "@/lib/actions";
import type { Project, Team } from "@/lib/api";

export type TreeRow =
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
export function tree(teams: Team[], projects: Project[]): TreeRow[] {
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

  const rows: TreeRow[] = [];
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
export function rootProjects(teams: Team[], projects: Project[]): Project[] {
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

export function TeamRow({
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
      <Menu
        label={`Actions for ${team.name}`}
        asChild
        trigger={
          <button type="button" className={rowActionsTriggerClass}>
            ⋯
          </button>
        }
        items={items}
      />
    </div>
  );
}

export function ProjectRow({
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
      <Menu
        label={`Actions for ${project.name}`}
        asChild
        trigger={
          <button type="button" className={rowActionsTriggerClass}>
            ⋯
          </button>
        }
        items={items}
      />
    </div>
  );
}
