"use client";

import { useMemo } from "react";
import { useCycles, useProjects, useUsers } from "@/lib/queries";
import { useTeamLabels } from "@/lib/queries/social";
import { useUi } from "@/store/ui";
import type { ViewFilters } from "@/lib/api";
import { FilterBar } from "./filter-bar";
import { FilterComposer } from "./filter-composer";

/**
 * The main list's question: the chips it is asking, and the control that adds one.
 *
 * A component of its own rather than sixty lines inside `app/page.tsx`, which is a 640-
 * line file three other things are being built into at once. It reads the store directly
 * because everything it needs is already there — the filters, the scope and the dialog —
 * so the page mounts it with no props and the wiring is one line in a file nobody else
 * has to merge around.
 *
 * The list is an unsaved saved view. Everything here is the saved-view screen's strip,
 * pointed at `store/ui.ts` instead of at a stored row; `SaveViewDialog` is what turns
 * the one into the other.
 */
export function ListFilters() {
  const scope = useUi((state) => state.scope);
  const filters = useUi((state) => state.filters);
  const setFilters = useUi((state) => state.setFilters);
  const dialog = useUi((state) => state.dialog);
  const openDialog = useUi((state) => state.openDialog);
  const close = useUi((state) => state.close);

  /**
   * Whose cycles and labels can be filtered on. Both are team-scoped, so an unscoped
   * list offers neither: the alternative is every team's labels in one list, where two
   * of them own the word `sync` and nothing on screen says which is which.
   */
  const teamId = scope.kind === "team" ? scope.id : undefined;

  const projects = useProjects();
  const users = useUsers();
  const cycles = useCycles(teamId);
  const labels = useTeamLabels(teamId);

  const names = useMemo(
    () => ({
      project: (id: string) => projects.data?.find((one) => one.id === id)?.name ?? id,
      person: (id: string) => users.data?.find((one) => one.id === id)?.displayName ?? id,
      cycle: (id: string) => {
        const cycle = cycles.data?.find((one) => one.id === id);
        return cycle ? `Cycle ${cycle.number}` : id;
      },
      label: (id: string) => labels.data?.find((one) => one.id === id)?.name ?? id,
    }),
    [projects.data, users.data, cycles.data, labels.data],
  );

  return (
    <>
      <FilterBar
        filters={filters}
        names={names}
        onAdd={() => openDialog({ kind: "filter" })}
        onSave={() => openDialog({ kind: "saveView" })}
        onFilters={setFilters}
        empty={
          <span className="text-12 text-faint">
            No filters — every ticket in the scope. The box on the right searches these.
          </span>
        }
      />

      {dialog.kind === "filter" && (
        <FilterComposer
          filters={filters}
          teamId={teamId}
          hide={scope.kind === "project" ? PROJECT_ANSWERED : undefined}
          onFilters={setFilters}
          onClose={close}
        />
      )}
    </>
  );
}

/**
 * A project scope has already answered the `project` facet, so it is not offered again —
 * `scopedFilters` in `queries/core.ts` is the other half of this and says what a second
 * answer would do to the list.
 *
 * A module constant so the composer's `useMemo` sees the same array every render.
 */
const PROJECT_ANSWERED: readonly (keyof ViewFilters)[] = ["project"];
