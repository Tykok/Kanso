"use client";

import { useUi } from "@/store/ui";
import type { ViewFilters } from "@/lib/api";
import { FILTER_INPUT_ID } from "@/lib/use-action-ctx";
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
  const query = useUi((state) => state.query);
  const setQuery = useUi((state) => state.setQuery);

  return (
    <FilterInput
      filters={filters}
      /**
       * The find-a-row box, which came down from the top bar to sit beside the question it
       * is not asking. Read off the store here rather than threaded from `app/(app)/page.tsx`
       * — the page reads `query` from exactly this store to narrow its own rows, so a prop
       * would only be the same value taking a longer road.
       *
       * `FILTER_INPUT_ID` moves with it, which is what keeps `/` landing here.
       *
       * Only mounted where this component is, and that is a *narrowing* of where the box
       * used to be: the top bar drew it on the timeline too, where nothing has ever read
       * `query` — `TimelineView` draws its own query and says so. A box that filtered
       * nothing is not a box worth keeping on a chart.
       */
      search={
        <input
          id={FILTER_INPUT_ID}
          className="w-[200px] shrink-0 rounded-md border border-transparent bg-accent px-2 py-1.5 text-12 max-[720px]:w-auto max-[720px]:flex-1"
          placeholder="Filter…  /"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              setQuery("");
              event.currentTarget.blur();
            }
          }}
        />
      }
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
        <span className="text-12 text-faint max-[720px]:hidden">
          No filters — every ticket in the scope. The box beside this line searches these.
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
