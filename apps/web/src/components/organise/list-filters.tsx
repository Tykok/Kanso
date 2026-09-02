"use client";

import { useUi } from "@/store/ui";
import type { ViewFilters } from "@/lib/api";
import { FilterInput } from "./filter-input";

/**
 * The main list's question: the line it is written on, and the chips of what it asks.
 *
 * A component of its own rather than twenty lines inside `app/(app)/page.tsx`, which is a
 * 600-line file. It reads the store directly because everything it needs is already there —
 * the filters and the scope — so the page mounts it with no props and the wiring is one
 * line in a file nobody else has to merge around.
 *
 * The list is an unsaved saved view. Everything here is the saved-view screen's strip,
 * pointed at `store/ui.ts` instead of at a stored row; `SaveViewDialog` is what turns the
 * one into the other. What used to be here as well — the composer dialog and the resolvers
 * for four kinds of id — is `filter-input.tsx`'s now, so that the two surfaces cannot spell
 * one vocabulary two ways.
 */
export function ListFilters() {
  const scope = useUi((state) => state.scope);
  const filters = useUi((state) => state.filters);
  const setFilters = useUi((state) => state.setFilters);
  const openDialog = useUi((state) => state.openDialog);

  return (
    <FilterInput
      filters={filters}
      /**
       * Whose cycles and labels can be asked about. Both are team-scoped, so an unscoped
       * list offers neither: the alternative is every team's labels in one list, where two
       * of them own the word `sync` and nothing on screen says which is which.
       */
      teamId={scope.kind === "team" ? scope.id : undefined}
      hide={scope.kind === "project" ? PROJECT_ANSWERED : undefined}
      onFilters={setFilters}
      onSave={() => openDialog({ kind: "saveView" })}
      empty={
        <span className="text-12 text-faint">
          No filters — every ticket in the scope. The box in the top bar searches these.
        </span>
      }
    />
  );
}

/**
 * A project scope has already answered the `project` facet, so it is not offered again —
 * `scopedFilters` in `queries/core.ts` is the other half of this and says what a second
 * answer would do to the list.
 *
 * A module constant so the box's `useMemo` sees the same array every render.
 */
const PROJECT_ANSWERED: readonly (keyof ViewFilters)[] = ["project"];
