"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";
import { actionById, type ActionContext } from "@/lib/actions";
import { api } from "@/lib/api";
import { keys } from "@/lib/queries";
import { useUi, type Scope } from "@/store/ui";
import { GroupLabel } from "./ui/group-label";
import { BrandMenu } from "./brand-menu";
import { ProjectRow, rootProjects, TeamRow, tree } from "./sidebar-tree";

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
    <aside className="flex w-full flex-col gap-[22px] bg-card px-2.5 py-4 overflow-y-auto">
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
              className="nav-add flex size-5 items-center justify-center rounded-sm normal-case tracking-normal text-faint hover:bg-accent hover:text-foreground"
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
              className="nav-add flex size-5 items-center justify-center rounded-sm normal-case tracking-normal text-faint hover:bg-accent hover:text-foreground"
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
              "size-3 shrink-0 rounded-sm border border-border",
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
