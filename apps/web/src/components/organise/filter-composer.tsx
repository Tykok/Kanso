"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Backdrop } from "@/components/overlays";
import { Kbd } from "@/components/ui/kbd";
import { TICKET_PRIORITIES, TICKET_STATUSES, type ViewFilters } from "@/lib/api";
import { useCycles, useProjects, useServedFilters, useUsers } from "@/lib/queries";
import { useTeamLabels } from "@/lib/queries/social";
import { PRIORITY_LABELS, STATUS_LABELS } from "@/lib/status";
import {
  composableFacets,
  filterEntries,
  withFacet,
  type FacetOption,
  type FacetSource,
} from "./facets";

/**
 * The "add a filter" control — the twelve facets the server serves, made reachable.
 *
 * ## One list, not twelve controls
 *
 * The facets differ in shape: some pick from a closed vocabulary, some from fetched
 * rows, one is a boolean, three are numeric bounds. They are drawn as *one* searchable
 * list of question-and-answer pairs — `Status is In progress`, `Label is sync`,
 * `Unassigned`, `Points at least 5` — because the shapes differ only in where the
 * answers come from, and `facets.ts` already reduces that difference to a
 * `Record<FacetSource, FacetOption[]>` this component fills in.
 *
 * The alternative was a menu of facets opening a second menu of answers, and it loses
 * twice. Somebody looking for the `sync` label knows the word `sync`, not that it is a
 * label, and the two-menu shape makes them find `Label` first. And a second menu is a
 * second place for the keyboard to be, in an application whose whole claim is that it
 * has one.
 *
 * ## Why this looks like the palette
 *
 * Because it is the same gesture: a field, a list under it, `↑↓↵`. `Backdrop` is the
 * palette's own frame, borrowed rather than restyled. The one difference is that `↵`
 * does not close: a question is usually more than one facet, and a control that shut
 * after each answer would be four openings to ask one thing.
 */
export function FilterComposer({
  filters,
  teamId,
  hide,
  onFilters,
  onClose,
}: {
  filters: ViewFilters;
  /**
   * Whose cycles and labels are on offer. Both are team-scoped, and two teams may own
   * the name `sync`: offering every team's would be a list of ids wearing the same word.
   */
  teamId?: string;
  /**
   * Facets this surface has already answered. The main list scoped to a project passes
   * `project`, because the scope *is* that filter and a second one could only widen past
   * it — see `scopedFilters` in `queries/core.ts`.
   */
  hide?: readonly (keyof ViewFilters)[];
  onFilters: (next: ViewFilters) => void;
  onClose: () => void;
}) {
  const served = useServedFilters();
  const projects = useProjects();
  const users = useUsers();
  const cycles = useCycles(teamId);
  const labels = useTeamLabels(teamId);

  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);

  const facets = useMemo(() => {
    const answered = new Set(hide ?? []);
    return composableFacets(served.data ?? []).filter((facet) => !answered.has(facet.key));
  }, [served.data, hide]);

  const options = useMemo<Partial<Record<FacetSource, FacetOption[]>>>(
    () => ({
      // The two closed vocabularies are already in the client — `TICKET_STATUSES` is a
      // literal in `lib/api/core.ts` and says why. It is the *facet* list that has to
      // come from the server, because that is the one nothing else in the client mirrors.
      status: TICKET_STATUSES.map((status) => ({ value: status, label: STATUS_LABELS[status] })),
      priority: TICKET_PRIORITIES.map((priority) => ({
        value: priority,
        label: PRIORITY_LABELS[priority],
      })),
      project: (projects.data ?? []).map((project) => ({
        value: project.id,
        label: project.name,
      })),
      assignee: (users.data ?? []).map((user) => ({
        value: user.id,
        label: user.displayName,
      })),
      cycle: (cycles.data ?? []).map((cycle) => ({
        value: cycle.id,
        label: `Cycle ${cycle.number}`,
      })),
      label: (labels.data ?? []).map((one) => ({ value: one.id, label: one.name })),
    }),
    [projects.data, users.data, cycles.data, labels.data],
  );

  const rows = useMemo(
    () => filterEntries(facets, options, filters, query),
    [facets, options, filters, query],
  );

  // Clamped rather than reset in an effect: the list narrows as somebody types and a
  // correction after the fact would render once with a highlight nobody can see.
  const at = Math.min(active, Math.max(rows.length - 1, 0));
  const current = rows[at];

  const listRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    listRef.current
      ?.querySelector<HTMLElement>('[data-active="true"]')
      ?.scrollIntoView({ block: "nearest" });
  }, [at, rows.length]);

  /** Toggling stays open, because one question is usually more than one facet. */
  const toggle = (row: (typeof rows)[number] | undefined) => {
    if (!row) return;
    onFilters(withFacet(filters, row.facet, row.value));
  };

  const chosen = rows.filter((row) => row.chosen).length;

  return (
    <Backdrop onClose={onClose} panelClassName="w-[min(560px,94vw)]">
      <div data-testid="filter-composer" className="flex flex-col">
        <div className="flex items-center gap-2.5 px-[18px] py-3.5">
          <span aria-hidden className="text-15 text-faint">
            ⌕
          </span>
          <input
            className="min-w-0 flex-1 border-none bg-transparent p-0 text-15 text-foreground outline-none placeholder:text-faint"
            autoFocus
            aria-label="Add a filter"
            placeholder="Filter by status, label, person, points…"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              // Back to the top on every retype, in the handler and not an effect: the
              // list under the field is a different list now.
              setActive(0);
            }}
            onKeyDown={(event) => {
              // `page.tsx` and `views/[id]` both answer bare keys on `window`. Nothing
              // typed here may also be moving a cursor or changing a status behind it.
              event.stopPropagation();
              if (event.key === "ArrowDown") {
                event.preventDefault();
                setActive(Math.min(at + 1, rows.length - 1));
              }
              if (event.key === "ArrowUp") {
                event.preventDefault();
                setActive(Math.max(at - 1, 0));
              }
              if (event.key === "Enter") {
                event.preventDefault();
                toggle(current);
              }
              if (event.key === "Escape") {
                event.preventDefault();
                onClose();
              }
            }}
          />
          {chosen > 0 && <span className="text-11 text-faint">{chosen} on</span>}
        </div>

        <div className="h-px bg-border" />

        <div ref={listRef} className="max-h-[42vh] min-h-[120px] overflow-y-auto py-1.5">
          {served.isPending ? (
            <p className="px-[18px] py-6 text-center text-12 text-faint">
              Asking the server which filters it serves…
            </p>
          ) : rows.length === 0 ? (
            <p className="px-[18px] py-6 text-center text-12 text-faint">
              {facets.length === 0
                ? "This server serves no filters."
                : `Nothing to filter by matches “${query}”.`}
            </p>
          ) : (
            rows.map((row, index) => (
              <button
                key={row.id}
                type="button"
                role="option"
                aria-selected={row.chosen}
                data-testid="filter-option"
                data-active={index === at}
                className={
                  index === at
                    ? "flex w-full items-center gap-2.5 bg-accent px-[18px] py-1.5 text-left text-13 text-foreground"
                    : "flex w-full items-center gap-2.5 px-[18px] py-1.5 text-left text-13 text-muted-foreground"
                }
                onMouseMove={() => setActive(index)}
                onClick={() => toggle(row)}
              >
                {/* The tick is what makes one row do both jobs: it says the answer is
                    already in the question, so pressing it again takes it out. */}
                <span
                  aria-hidden
                  className={
                    row.chosen
                      ? "grid size-3.5 shrink-0 place-items-center rounded-sm bg-primary text-[9px] text-primary-foreground"
                      : "size-3.5 shrink-0 rounded-sm border border-border"
                  }
                >
                  {row.chosen ? "✓" : ""}
                </span>
                <span className="truncate">{row.label}</span>
              </button>
            ))
          )}
        </div>

        <div className="h-px bg-border" />
        <div className="flex items-center gap-2.5 px-4 py-2.5 text-11 text-faint">
          <span>
            <Kbd>↑</Kbd> <Kbd>↓</Kbd> move
          </span>
          <span>
            <Kbd>↵</Kbd> add or remove
          </span>
          <span>
            <Kbd>esc</Kbd> done
          </span>
          <span className="flex-1" />
          <span role="status">{rows.length} to choose from</span>
        </div>
      </div>
    </Backdrop>
  );
}
