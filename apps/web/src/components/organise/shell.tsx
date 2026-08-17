"use client";

import { useCallback, useMemo, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { BrandSplash } from "@/components/brand-logo";
import { MobileNavDrawer } from "@/components/mobile-nav";
import { Sidebar } from "@/components/sidebar";
import { ApiError } from "@/lib/api";
import { useMe, usePreferences, useSyncStatus, useTeams } from "@/lib/queries";
import { useActionContext } from "@/lib/use-action-ctx";
import { useUi } from "@/store/ui";

/**
 * The chrome the four organising routes render inside.
 *
 * `app/page.tsx` builds the shell inline and is frozen for the fan-out, and
 * `app/layout.tsx` renders nothing but `<Providers>`, so a route that wants the sidebar has
 * to mount it itself. This is that, and nothing more: the sidebar, a breadcrumb bar, and
 * the two gates a page without a session needs. Promoting it to an `app/(app)/layout.tsx`
 * would mean `page.tsx` adopting it, which is the edit the fan-out forbids — worth doing in
 * the integration pass, not from a branch that cannot see the other five.
 */
export function OrganiseShell({
  breadcrumb,
  aside,
  trailing,
  children,
}: {
  /** `Core / Cycle 24`, as the drawing has it. The team is prepended here. */
  breadcrumb: ReactNode;
  /**
   * The cycle list on screen 19, the saved-view list on 21, the queue on 20.
   *
   * The drawings put the first two inside the main sidebar, under their own headings.
   * `sidebar.tsx` takes `ctx`, `syncSummary` and `onNavigate` and nothing else, and it is
   * read-only for the fan-out, so they are drawn as a rail at the left of the content
   * instead — which is the shape screen 20 already uses for its queue. Matching the
   * drawing exactly needs one slot prop on the sidebar; that is a shared edit and it is
   * named in the branch's report rather than made here.
   */
  aside?: ReactNode;
  /** The right end of the breadcrumb bar: a date range, a count, `Shared with the team`. */
  trailing?: ReactNode;
  children: ReactNode;
}) {
  const router = useRouter();
  const me = useMe();
  const preferences = usePreferences();
  const sync = useSyncStatus();
  const ctx = useOrganiseContext();

  const summary = !sync.data
    ? ""
    : sync.data.mirrorEnabled
      ? `Notion: ${sync.data.bootstrapped ? "connected" : "not bootstrapped"}`
      : "Notion mirror off";

  if (me.isLoading) return <BrandSplash label="Loading…" />;
  // Signing in happens on `/`, which is where the login screen lives. Rendering a second
  // copy of it here would be two places to keep one flow correct.
  if (me.error instanceof ApiError && me.error.status === 401) {
    router.replace("/");
    return <BrandSplash label="Signing in…" />;
  }

  return (
    <div
      className={
        preferences.sidebarVisible
          ? "grid h-screen grid-cols-[248px_1fr] max-[720px]:grid-cols-[1fr]"
          : "grid h-screen grid-cols-[1fr]"
      }
    >
      {preferences.sidebarVisible && (
        <div className="contents max-[720px]:hidden">
          <Sidebar ctx={ctx} syncSummary={summary} />
        </div>
      )}

      <div className="flex min-h-0 min-w-0 flex-col">
        <div className="flex items-center gap-2.5 bg-card px-6 py-3 text-12 text-faint">
          <MobileNavDrawer ctx={ctx} syncSummary={summary} />
          {breadcrumb}
          <span className="flex-1" />
          {trailing}
        </div>

        {aside === undefined ? (
          children
        ) : (
          <div className="grid min-h-0 flex-1 grid-cols-[240px_1fr] max-[720px]:grid-cols-[1fr]">
            <div className="flex min-h-0 flex-col gap-1 overflow-y-auto bg-card px-2 py-3 max-[720px]:hidden">
              {aside}
            </div>
            {children}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Which team these screens are about.
 *
 * A cycle, a triage queue, a saved view and a workload chart all belong to exactly one
 * team, and none of the four has a URL that names it. The sidebar's scope is the answer
 * when it holds one; otherwise the preferred team, otherwise the first the instance has.
 * A project scope falls through to the same fallback — a project can span teams, and there
 * is no honest way to read one team out of it.
 */
export function useOrganiseTeam() {
  const scope = useUi((state) => state.scope);
  const preferences = usePreferences();
  const teams = useTeams();

  return useMemo(() => {
    const all = teams.data ?? [];
    const scoped = scope.kind === "team" ? all.find((team) => team.id === scope.id) : undefined;
    const preferred = preferences.defaultTeamId
      ? all.find((team) => team.id === preferences.defaultTeamId)
      : undefined;
    return { team: scoped ?? preferred ?? all[0], teams: all, isLoading: teams.isLoading };
  }, [scope, preferences.defaultTeamId, teams.data, teams.isLoading]);
}

/**
 * An `ActionContext` for the sidebar, which needs one to draw its `+` buttons and row
 * menus.
 *
 * The seven list-local callbacks `useActionContext` asks for are all about a ticket list
 * these screens do not have, so they are inert here. That is not a stub standing in for
 * something missing: `move` and `startRename` describe a cursor over rows, and a cycle
 * report has no cursor. The keyboard on these pages is handled by the pages themselves.
 */
function useOrganiseContext() {
  const [, setError] = useState<string | null>(null);
  const noop = useCallback(() => {}, []);
  return useActionContext({
    tickets: [],
    selected: undefined,
    move: noop,
    startRename: noop,
    startLink: noop,
    startUnlink: noop,
    reportError: setError,
  });
}
