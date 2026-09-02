"use client";

import { cn } from "@/lib/utils";
import { PROJECT_HEALTH_COLORS } from "@/lib/status";
import { healthLabel } from "./views/project-copy";
import { useMenuItems } from "./menu-items";
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
  const items = useMenuItems(ctx, [
    // `ctx` here is already scoped to this row, so the toggle acts on this team and not on
    // whatever the list happens to be showing — the same trick every other id in this
    // array relies on.
    "favourite.toggle",
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
  const items = useMenuItems(ctx, [
    "favourite.toggle",
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
      data-health={project.health ?? "none"}
    >
      <button
        className={cn(
          "flex min-w-0 flex-1 items-center gap-2 py-[5px] text-left",
          DEPTH_PAD[depth],
          project.archived && "opacity-55",
        )}
        aria-current={current}
        onClick={onSelect}
        /**
         * The health in the `title`, beside the name.
         *
         * A one-pixel dot cannot carry a word, and the rule `ui/status-dot.tsx` sets out
         * at length is that the hue is never the only signal — so the health is spelled
         * out in the text this row already carries, rather than left to the colour alone.
         */
        title={project.health ? `${project.name} — ${healthLabel(project.health)}` : project.name}
      >
        {/*
          * The bullet that has always marked a project row, now in the colour of its
          * health.
          *
          * A real element rather than the `before:` pseudo it used to be, because the
          * colour is per-project data and a pseudo-element can only be reached through a
          * custom property — which would be a design token invented for one dot, and
          * `lib/tokens.test.ts` is right to refuse those.
          *
          * This is the only place in the app where every project is listed at once, which
          * makes it the only place "which of these is in trouble" can be answered by
          * looking instead of by visiting five pages. The *public* roadmap under
          * `app/roadmap/` is a shop window over published tickets, so it is not that
          * place — see the note on `ProjectHealthPanel`. A project nobody has assessed
          * keeps the faint dot it has always had: absence must not read as good news.
          */}
        <span
          aria-hidden
          className="size-1 shrink-0 rounded-full"
          style={{
            background: project.health ? PROJECT_HEALTH_COLORS[project.health] : "var(--faint)",
          }}
        />
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
