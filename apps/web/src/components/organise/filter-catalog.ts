"use client";

import { useMemo } from "react";
import type { ViewFilters } from "@/lib/api";
import { namesFrom, type FilterCatalog, type FilterNames, type FilterOption } from "@/lib/filter-query";
import { useCycles, useMe, useProjects, useServedFilters, useUsers } from "@/lib/queries";
import { useTeamLabels } from "@/lib/queries/social";
import type { ChipNames } from "./chips";
import { filterOptions } from "./filter-text";

/**
 * The vocabulary this instance has, from the five queries that already answer it.
 *
 * `lib/filter-query/` is deliberately ignorant of where a catalogue comes from — the text,
 * the caret and a catalogue are its whole input, which is what lets the language be a unit
 * test rather than a browser. This is the other half: the five queries, in the one shape it
 * reads. Its own file because it is a different concern from editing a line, and because
 * both halves of the round trip are built here in one place, from one set of rows.
 *
 * ## Three name resolvers off one catalogue
 *
 * A stored id has to print two different ways and be read a third. `namesFrom` gives the
 * *tokens* — `format` has to write `design-system`, because what it writes has to parse.
 * `chipNames` gives the *names* — a chip prints `Design system`, and a uuid in a chip is
 * the one thing that must never appear on screen. Deriving both from the same catalogue is
 * what keeps them from drifting: there is one list of rows and one spelling of each.
 */
export type Vocabulary = {
  catalog: FilterCatalog;
  /** Ids to tokens, for `format`. */
  names: FilterNames;
  /** Ids to names, for the chips. */
  chipNames: ChipNames;
  /**
   * Whether a query the catalogue needs has yet to answer.
   *
   * The box reads it for one thing only: not underlining anything yet. `served` gates
   * `suggest` and deliberately not `parse`, so a filter stored as a uuid does print as a
   * uuid for the paint before the projects query lands — which is what the chips have always
   * done. Underlining it red in the meantime would be the box calling the server's own
   * answer a typo.
   */
  settling: boolean;
};

export function useFilterVocabulary(
  /** Whose cycles and labels are on offer. Both are team-scoped; an unscoped list has none. */
  teamId?: string,
  /** Facets the surface has already answered, hidden from what `suggest` offers. */
  hide?: readonly (keyof ViewFilters)[],
): Vocabulary {
  const served = useServedFilters();
  const projects = useProjects();
  const users = useUsers();
  const cycles = useCycles(teamId);
  const labels = useTeamLabels(teamId);
  const me = useMe();

  const catalog = useMemo<FilterCatalog>(() => {
    const answered = new Set<string>(hide ?? []);
    return {
      served: (served.data ?? []).filter((facet) => !answered.has(facet)),
      options: {
        project: filterOptions(projects.data ?? []),
        assignee: filterOptions(
          (users.data ?? []).map((user) => ({ id: user.id, name: user.displayName })),
        ),
        // A cycle is spelled by its number rather than slugged from a name, because
        // `cycle:24` is what the design's own table writes and a cycle has no name to slug.
        cycle: (cycles.data ?? []).map((cycle) => ({
          token: String(cycle.number),
          id: cycle.id,
          label: `Cycle ${cycle.number}`,
        })),
        label: filterOptions(labels.data ?? []),
      },
      me: me.data?.user.id,
      // `cycle:current` resolves to this id and stores it, which is why `format` prints the
      // number back rather than the word: the filter does not follow the cycle boundary.
      currentCycleId: (cycles.data ?? []).find((cycle) => cycle.state === "active")?.id,
    };
  }, [served.data, hide, projects.data, users.data, cycles.data, labels.data, me.data]);

  const names = useMemo(() => namesFrom(catalog), [catalog]);
  const chipNames = useMemo<ChipNames>(
    () => ({
      project: (id) => labelOf(catalog.options.project, id),
      person: (id) => labelOf(catalog.options.assignee, id),
      cycle: (id) => labelOf(catalog.options.cycle, id),
      label: (id) => labelOf(catalog.options.label, id),
    }),
    [catalog],
  );

  return {
    catalog,
    names,
    chipNames,
    settling:
      served.isPending ||
      projects.isPending ||
      users.isPending ||
      me.isPending ||
      // Disabled rather than slow, with no team: a query that is never going to run stays
      // `isPending` forever, and reading it unguarded would mean the unscoped list never
      // underlined anything at all.
      (teamId !== undefined && (cycles.isPending || labels.isPending)),
  };
}

/** A stored id, as the name a reader recognises. The token is the fallback, the id the last. */
const labelOf = (options: readonly FilterOption[] | undefined, id: string) => {
  const found = options?.find((option) => option.id === id);
  return found?.label ?? found?.token ?? id;
};
