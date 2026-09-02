"use client";

import { useMemo } from "react";
import { useSearchParams } from "next/navigation";
import { usePreferences, useTeams } from "@/lib/queries";
import { useUi } from "@/store/ui";

/**
 * Which team these screens are about.
 *
 * A cycle, a triage queue and a workload chart all belong to exactly one team, and none of
 * the three has a path that names it — `/views/[id]` is the exception, because a saved view
 * carries its own id. So the answer is looked up in four places, in this order:
 *
 * 1. `?team=` in the URL. `navHref` in `lib/nav.ts` puts it on every one of these links
 *    while a team is selected, and `AppShell` hydrates the scope back out of it on
 *    arrival — so the parameter is now the seam between the address and the store rather
 *    than a workaround bolted onto this hook. It exists because the scope lives in a
 *    zustand store that a page load wipes: without it, reloading `/cycles/current` — or
 *    following a link somebody pasted — silently showed a *different* team's cycle, with
 *    nothing on screen admitting the substitution. A URL that cannot say what it is about
 *    is not a link.
 * 2. The sidebar's scope, which is what clicking through the app sets.
 * 3. The preferred team from the preferences.
 * 4. The first team the instance has.
 *
 * A project scope falls through to the fallback — a project can span teams, and there is no
 * honest way to read one team out of it.
 *
 * It lives in a file of its own because the shell it used to sit in is gone. Not moved into
 * `lib/`: it is not a query and not a rule, it is these four screens' answer to a question
 * only they ask, and the four-step fallback is unchanged from the day it was written.
 */
export function useOrganiseTeam() {
  const scope = useUi((state) => state.scope);
  const preferences = usePreferences();
  const teams = useTeams();
  const requested = useSearchParams().get("team");

  return useMemo(() => {
    const all = teams.data ?? [];
    // An id that names no team is ignored rather than fatal: a stale link should land on
    // something readable, and the header names whichever team was resolved.
    const named = requested ? all.find((team) => team.id === requested) : undefined;
    const scoped = scope.kind === "team" ? all.find((team) => team.id === scope.id) : undefined;
    const preferred = preferences.defaultTeamId
      ? all.find((team) => team.id === preferences.defaultTeamId)
      : undefined;
    return {
      team: named ?? scoped ?? preferred ?? all[0],
      teams: all,
      isLoading: teams.isLoading,
    };
  }, [requested, scope, preferences.defaultTeamId, teams.data, teams.isLoading]);
}
