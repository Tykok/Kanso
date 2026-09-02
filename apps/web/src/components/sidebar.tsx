"use client";

import { useMemo, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { PanelLeft } from "lucide-react";
import { cn } from "@/lib/utils";
import { actionById, permits, type ActionContext } from "@/lib/actions";
import { api } from "@/lib/api";
import {
  currentSelection,
  isCurrentItem,
  isCurrentScope,
  navHref,
  type NavSelection,
} from "@/lib/nav";
import { keys, usePreferences, useSavePreferences } from "@/lib/queries";
import { useUi, type Scope } from "@/store/ui";
import { GroupLabel } from "./ui/group-label";
import { BrandMenu } from "./brand-menu";
import { Favourites } from "./favourites";
import { OnboardingChecklist } from "./inbox/onboarding-checklist";
import { isNavRow, NAV_ITEMS, type NavItem } from "./nav-items";
import { ProjectRow, rootProjects, TeamRow, tree } from "./sidebar-tree";

/**
 * The Views group's order: `/me`, then `All tickets`, then every other route.
 *
 * Two filters over one list rather than an index into it, because the order is a claim
 * about *which* row goes first and `NAV_ITEMS[0]` is only a claim about position — a row
 * inserted above `me` by a later slice would silently change what "the personal home" is.
 * `All tickets` is not in the list at all: it selects a scope rather than navigating, so
 * it has no href, which is why the split has to happen here and not there.
 */
const PERSONAL_ROWS = NAV_ITEMS.filter((item) => isNavRow(item) && item.id === "me");
const ROUTE_ROWS = NAV_ITEMS.filter((item) => isNavRow(item) && item.id !== "me");

/**
 * A row in the Views group: the box, the ink and the two data attributes the selection is
 * read off — nothing about where it goes.
 *
 * Extracted because three rows now share it and the first two were already a copy of each
 * other. `All tickets` is a `<button>` that changes the scope and the rest are `<Link>`s,
 * which is the whole of what differs, so the child is the caller's and the frame is here.
 * `nav.test.ts` proves the *rule*; e2e/23 counts `[data-current=true]` over the DOM, and
 * one frame is what makes that count trustworthy.
 */
function ViewRow({ current, children }: { current: boolean; children: ReactNode }) {
  return (
    <div
      data-testid="nav-item"
      className={cn(
        "nav-item",
        "flex items-center gap-1 rounded-md pr-1.5",
        current
          ? "bg-accent-soft font-medium text-foreground"
          : "text-muted-foreground hover:bg-accent",
      )}
      data-kind="view"
      data-current={current}
    >
      {children}
    </div>
  );
}

/** A row that is a destination: a link, lit when the route it names is the selection. */
function RouteRow({
  item,
  scope,
  selection,
  onNavigate,
}: {
  item: NavItem;
  scope: Scope;
  selection: NavSelection;
  onNavigate?: () => void;
}) {
  const current = isCurrentItem(item, selection);
  return (
    <ViewRow current={current}>
      <Link
        className="min-w-0 flex-1 py-[5px] pl-1.5 text-left"
        href={navHref(item, scope)}
        aria-current={current}
        onClick={onNavigate}
      >
        <span className="truncate">{item.label}</span>
      </Link>
    </ViewRow>
  );
}

/**
 * `PanelLeft` beside the seal: how this column is anchored.
 *
 * Two directions, one control, and which one it is comes from the mode rather than from a
 * prop — the pinned column is only ever drawn while the mode is `pinned`, and the reveal
 * only while it is not, so the same component answers correctly in both without either
 * caller having to say which it is. `MobileNavDrawer` renders this component too, where
 * the mode means nothing; `max-[720px]:hidden` is why the button is not there.
 *
 * It writes `pinned` and `hover` and never `hidden`. A third state you discover by
 * pressing a button twice is the kind of control this pass exists to remove, so `hidden`
 * is reachable from Appearance alone — where it is one of three labelled choices with a
 * hint that says what it leaves you.
 */
function SidebarAnchor() {
  const pinned = usePreferences().sidebarMode === "pinned";
  const save = useSavePreferences();

  return (
    <button
      type="button"
      data-testid="sidebar-anchor"
      aria-label={pinned ? "Collapse the sidebar to the left edge" : "Pin the sidebar"}
      title={pinned ? "Collapse — reveals from the left edge" : "Pin"}
      className="grid size-6 shrink-0 place-items-center rounded-sm text-faint hover:bg-accent hover:text-foreground max-[720px]:hidden"
      onClick={() => save.mutate({ sidebarMode: pinned ? "hover" : "pinned" })}
    >
      <PanelLeft size={14} aria-hidden />
    </button>
  );
}

/**
 * `onNavigate` fires beside every `setScope` call, never on its own: the row
 * actions (rename, archive, the `+` buttons) do not navigate, so they must not
 * close whatever is hosting this component. The desktop column has nothing to
 * close and leaves it unset; `MobileNavDrawer` passes its own `onClose` — picking
 * a destination is what the drawer is for, and a modal that stays open over the
 * page it was just asked to leave is not doing its one job.
 */
export function Sidebar({
  ctx,
  syncSummary,
  onNavigate,
}: {
  ctx: ActionContext;
  syncSummary: string;
  onNavigate?: () => void;
}) {
  const { scope, setScope, showArchived, setShowArchived } = useUi();
  const pathname = usePathname();
  const router = useRouter();

  /**
   * One question, asked once, and every row below reads its answer. The column used to
   * hold two rules — the Views rows compared the pathname to their own href, the scope
   * rows compared the store's scope to their own subject — and neither knew about the
   * other, which is why `/docs` lit **Documents** and **All tickets** at the same time.
   */
  const selection = currentSelection(pathname, scope);

  /**
   * Picking a subject takes you to it.
   *
   * `setScope` alone was the click the maintainer called "pas fou": from `/cycles/current`
   * it set a scope nothing on screen was drawing, and the reader stayed on the cycle
   * wondering what had happened. The list is where a scope is legible, so that is where
   * the row goes — and only if it is not already there, since pushing `/` onto `/` would
   * spend a history entry to arrive where you are and put `Escape` one press further from
   * anywhere.
   */
  const selectScope = (next: Scope) => {
    setScope(next);
    if (pathname !== "/") router.push("/");
    onNavigate?.();
  };

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

  /**
   * `favourite` beside `scope`, and it has to be spelled out rather than derived from the
   * scope by the action: a row's menu names a team or a project that is not necessarily
   * what the page is showing, and `favourite.toggle` opened from one while a saved view is
   * on screen would otherwise pin the view behind it.
   */
  const at = (next: Scope): ActionContext => ({
    ...ctx,
    scope: next,
    favourite: next.kind === "all" ? undefined : { kind: next.kind, id: next.id },
  });

  /**
   * The two header `+` buttons create at the root, explicitly: a team with no parent,
   * a project with no team. They therefore get a context scoped to "all" rather than
   * the current selection, which would make the result depend on whatever happens to
   * be open.
   */
  const rootCtx = at({ kind: "all" });
  const newTeam = actionById("team.create");
  const newProject = actionById("project.create");

  const allCurrent = isCurrentScope({ kind: "all" }, selection);

  return (
    <aside className="flex w-full flex-col gap-[22px] bg-card px-2.5 py-4 overflow-y-auto">
      {/* The seal takes the width it is given, so it is the one that flexes and the
          anchor button keeps its 24px beside it. */}
      <div className="flex items-center gap-1">
        <div className="min-w-0 flex-1">
          <BrandMenu ctx={rootCtx} />
        </div>
        <SidebarAnchor />
      </div>

      {/* Above everything: what somebody pinned is what they came back for. Draws nothing
          at all until there is a pin, so an empty instance is the column it always was. */}
      <Favourites onNavigate={onNavigate} />

      <div>
        <GroupLabel className="pt-0">Views</GroupLabel>

        {/* A route rather than a scope, like every row but the next one. `live` is what
            keeps a half-built one out of the column and `row` is what keeps a finished
            one out — the inbox is a bell in the top bar now, and `isNavRow` is the single
            place that knows both.

            `navHref` rather than `item.href`: while a team is selected, these links carry
            it forward as `?team=`, so going from a team's list to that team's cycle keeps
            the subject across the page load that wipes the store. */}
        {PERSONAL_ROWS.map((item) => (
          <RouteRow key={item.id} item={item} scope={scope} selection={selection} onNavigate={onNavigate} />
        ))}

        {/* The one row in this group that goes nowhere: it changes what the list is
            about, which is why it is a `<button>` and why it lives in the markup rather
            than in `NAV_ITEMS` — it has no href to put there. */}
        <ViewRow current={allCurrent}>
          <button
            className="min-w-0 flex-1 py-[5px] pl-1.5 text-left"
            aria-current={allCurrent}
            onClick={() => selectScope({ kind: "all" })}
          >
            <span className="truncate">All tickets</span>
          </button>
        </ViewRow>

        {ROUTE_ROWS.map((item) => (
          <RouteRow key={item.id} item={item} scope={scope} selection={selection} onNavigate={onNavigate} />
        ))}
      </div>

      <div>
        <GroupLabel className="flex items-center gap-1.5">
          <span className="flex-1">Teams</span>
          {permits(newTeam, rootCtx) && (
            <button
              className="flex size-5 items-center justify-center rounded-sm normal-case tracking-normal text-faint hover:bg-accent hover:text-foreground"
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
              current={isCurrentScope({ kind: "team", id: row.team.id }, selection)}
              ctx={at({ kind: "team", id: row.team.id })}
              onSelect={() => selectScope({ kind: "team", id: row.team.id })}
            />
          ) : (
            <ProjectRow
              key={`project-${row.project.id}`}
              project={row.project}
              depth={row.depth}
              current={isCurrentScope({ kind: "project", id: row.project.id }, selection)}
              ctx={at({ kind: "project", id: row.project.id })}
              onSelect={() => selectScope({ kind: "project", id: row.project.id })}
            />
          ),
        )}
      </div>

      <div>
        <GroupLabel className="flex items-center gap-1.5">
          <span className="flex-1">Projects</span>
          {permits(newProject, rootCtx) && (
            <button
              className="flex size-5 items-center justify-center rounded-sm normal-case tracking-normal text-faint hover:bg-accent hover:text-foreground"
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
            current={isCurrentScope({ kind: "project", id: project.id }, selection)}
            ctx={at({ kind: "project", id: project.id })}
            onSelect={() => selectScope({ kind: "project", id: project.id })}
          />
        ))}
      </div>

      <div className="mt-auto flex flex-col gap-2 px-1.5">
        {/* Screen 08's four gestures. It renders nothing once they are done, so this
            needs no condition of its own — the checklist is the only thing that knows
            whether it is finished. The one condition it does need is the seat: all four
            gestures are writes, and homework a reader cannot do would never be finished
            and so would never go away. */}
        {rootCtx.canWrite && <OnboardingChecklist />}

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
