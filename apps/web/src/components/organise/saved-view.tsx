"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { Menu } from "@/components/menu";
import { GROUP_LABEL_ESTIMATE, GroupLabel } from "@/components/ui/group-label";
import { PriorityMark } from "@/components/ui/priority-mark";
import { Row } from "@/components/ui/row";
import { actionErrorMessage } from "@/lib/errors";
import {
  VIEW_GROUP_BYS,
  VIEW_SORT_BYS,
  type SavedView,
  type TicketGroup,
  type ViewGroupBy,
  type ViewSortBy,
} from "@/lib/api";
import {
  useBulkDelete,
  useBulkEdit,
  useCycles,
  usePatchView,
  useProjects,
  useSavedView,
  useSavedViews,
  useUsers,
  useViewGroups,
} from "@/lib/queries";
import { useTeamLabels } from "@/lib/queries/social";
import { useRowMetrics } from "@/lib/row-metrics";
import { PRIORITY_LABELS } from "@/lib/status";
import { flatIndexOf, flatten, sizeAt } from "@/lib/virtual";
import { useUi } from "@/store/ui";
import { BulkStrip } from "./bulk-strip";
import type { ChipNames } from "./chips";
import { FilterBar } from "./filter-bar";
import { FilterComposer } from "./filter-composer";
import { nameGroups } from "./grouping";
import { FavouriteStar } from "../favourites";
import { OrganiseShell, useOrganiseTeam } from "./shell";
import { ViewRail } from "./view-rail";
import { extend, toggle } from "./selection";

/**
 * Screen 21 — a saved view, its removable chips, and six rows edited at once.
 *
 * The selection lives here rather than in `store/ui.ts`: it belongs to one list on one
 * route, it must not survive navigating away, and `store/ui.ts` is closed for the fan-out
 * anyway. The `bulk` overlay slice 0 reserved is deliberately not used — the strip is not
 * an overlay, it does not trap focus, and marking one open would make `esc` close it
 * through `useUi().close()` at the same time as this page's own handler.
 */
export function SavedViewScreen({ id }: { id: string }) {
  const { team: resolved, teams } = useOrganiseTeam();
  const view = useSavedView(id);
  /**
   * The buckets, stacked and counted by the server.
   *
   * This screen used to fetch a flat page and bucket it here, which made every group
   * header a count of the fetch rather than of the view: a view matching two thousand
   * tickets drew `Todo · 29` where twenty-nine was how many of the two hundred rows it
   * had been sent were todo. The rows below are the same rows — the flat page is this
   * answer's `tickets` concatenated — so nothing else on the screen changes shape.
   */
  const rows = useViewGroups(id);
  /**
   * The view's own team, not the one the sidebar happens to be scoped to.
   *
   * This screen is the one of the four that names its subject in the path, and a saved view
   * carries `teamId` — so nothing here has to be guessed. It used to read `useOrganiseTeam`,
   * which falls back to the first team in the instance when a page load has wiped the scope:
   * the view itself resolved correctly (its id is in the URL) while its cycles, its people
   * and its *labels* came from a stranger's team, so the strip's Label button silently
   * vanished and the `Étiquette` chip could not resolve a name. `resolved` stays as the
   * fallback for the moment before the view lands.
   */
  const team = view.data
    ? (teams.find((candidate) => candidate.id === view.data.teamId) ?? {
        id: view.data.teamId,
        name: "…",
      })
    : resolved;

  const views = useSavedViews(team?.id);
  const cycles = useCycles(team?.id);
  const users = useUsers();
  const projects = useProjects();
  // The team's labels, for two things at once: the `Étiquette synchro` chip resolves an id
  // to a name through them, and the strip's sixth button offers them.
  const labels = useTeamLabels(team?.id);
  const patch = usePatchView();
  const bulkEdit = useBulkEdit();
  const bulkDelete = useBulkDelete();

  const [selected, setSelected] = useState<string[]>([]);
  const [cursor, setCursor] = useState<string>();
  const [error, setError] = useState<string | null>(null);
  /**
   * The composer is a store dialog rather than local state, and `F` reaches it from this
   * page's own handler rather than through the registry, for the two reasons this file
   * already gives about `x` and `⇧↑↓`: `resolveShortcut` is dispatched by `app/page.tsx`
   * and this route is not that page. The action exists all the same — `organise.addFilter`
   * — so the help sheet says the key is there, and the button below is the same door.
   */
  const { dialog, openDialog, close } = useUi();

  /**
   * The page flattened back out, for everything that walks the list rather than draws it
   * — the cursor, `⇧↑↓`, the empty state. It is the flat answer, in the order the server
   * stacked it, so `j` still runs straight down the screen through the headers.
   */
  const tickets = useMemo(
    () => (rows.data?.groups ?? []).flatMap((group) => group.tickets),
    [rows.data],
  );
  // Keyed on `tickets`, which is already memoised on `rows.data`: the `?? []` above makes
  // a fresh array every render, and memoising against that would recompute every time and
  // rebuild every callback below.
  const ids = useMemo(() => tickets.map((ticket) => ticket.id), [tickets]);

  const names = useMemo(
    () => ({
      project: (projectId: string) =>
        projects.data?.find((project) => project.id === projectId)?.name ?? projectId,
      person: (userId: string) =>
        users.data?.find((user) => user.id === userId)?.displayName ?? userId,
      cycle: (cycleId: string) => {
        const cycle = cycles.data?.find((each) => each.id === cycleId);
        return cycle ? `Cycle ${cycle.number}` : cycleId;
      },
      label: (labelId: string) =>
        labels.data?.find((label) => label.id === labelId)?.name ?? labelId,
    }),
    [users.data, cycles.data, projects.data, labels.data],
  );

  const clear = useCallback(() => {
    setSelected([]);
    setError(null);
  }, []);

  const move = useCallback(
    (delta: number) => {
      if (ids.length === 0) return;
      const at = cursor === undefined ? -1 : ids.indexOf(cursor);
      setCursor(ids[Math.min(Math.max(at + delta, 0), ids.length - 1)]);
    },
    [cursor, ids],
  );

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target;
      if (target instanceof HTMLElement && ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)) return;

      // Nothing behind an open dialog: the composer owns `↑↓↵` while it is up, and this
      // list must not walk its own rows underneath it.
      if (dialog.kind !== "none") return;

      if (event.key === "Escape") {
        clear();
        return;
      }
      if (event.key === "F") {
        event.preventDefault();
        openDialog({ kind: "filter" });
        return;
      }
      // `⇧↑↓`: the range grows and the cursor follows it, in one keypress. `event.key` for
      // Shift+ArrowDown is still `ArrowDown`, which is why this cannot be a registry
      // shortcut — the registry has no modifier state to read.
      if (event.shiftKey && (event.key === "ArrowDown" || event.key === "ArrowUp")) {
        event.preventDefault();
        const delta = event.key === "ArrowDown" ? 1 : -1;
        setSelected((current) => extend(ids, current, cursor, delta));
        move(delta);
        return;
      }
      if (event.key === "j" || event.key === "ArrowDown") {
        event.preventDefault();
        move(1);
        return;
      }
      if (event.key === "k" || event.key === "ArrowUp") {
        event.preventDefault();
        move(-1);
        return;
      }
      if (event.key === "x" && cursor !== undefined) {
        event.preventDefault();
        setSelected((current) => toggle(current, cursor));
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  });

  const onDone = { onError: (failure: unknown) => setError(actionErrorMessage(failure)), onSuccess: clear };
  const edit = (change: Parameters<typeof bulkEdit.mutate>[0]) => bulkEdit.mutate(change, onDone);

  return (
    <OrganiseShell
      breadcrumb={
        <>
          <span>{team?.name ?? "…"}</span>
          <span>/</span>
          <span className="text-muted-foreground">{view.data?.name ?? "Saved view"}</span>
          {/* Beside the name, for the reason the document's is: this screen's key handler
              is hand-written and never reaches the registry, and `OrganiseShell` mounts no
              command palette, so `s` has nowhere to land here. */}
          {view.data && <FavouriteStar target={{ kind: "view", id: id }} label={view.data.name} />}
        </>
      }
      trailing={view.data && <span>{view.data.shared ? "Shared with the team" : "Only yours"}</span>}
      aside={<ViewRail views={views.data ?? []} currentId={id} />}
    >
      <div className="relative flex min-h-0 flex-1 flex-col">
        {view.data && (
          <Chips
            view={view.data}
            names={names}
            onAdd={() => openDialog({ kind: "filter" })}
            onFilters={(filters) => patch.mutate({ id, filters })}
            onGroupBy={(groupBy) => patch.mutate({ id, groupBy })}
            onSortBy={(sortBy) => patch.mutate({ id, sortBy })}
          />
        )}

        {dialog.kind === "filter" && view.data && (
          <FilterComposer
            filters={view.data.filters}
            teamId={team?.id}
            /**
             * Written straight through to the view, not held and saved on closing. A
             * saved view is a stored question and this *is* the question — every other
             * edit on this screen lands the same way, and a composer with its own draft
             * would be the one place on it where what is on screen is not what is stored.
             */
            onFilters={(filters) => patch.mutate({ id, filters })}
            onClose={close}
          />
        )}

        {error && (
          <div className="topbar-error" role="alert">
            <span>{error}</span>
          </div>
        )}

        {rows.isPending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

        {!rows.isPending && tickets.length === 0 && (
          <div className="empty flex-col gap-1">
            <span className="text-13 text-foreground">Nothing matches this view</span>
            <span className="text-12 text-faint">
              It is the filters, not the database — remove a chip to widen it.
            </span>
          </div>
        )}

        <Rows
          groups={rows.data?.groups ?? []}
          groupBy={rows.data?.groupBy ?? view.data?.groupBy ?? "status"}
          names={names}
          selected={selected}
          cursor={cursor}
          onRow={(ticketId) => {
            setCursor(ticketId);
            setSelected((current) => toggle(current, ticketId));
          }}
        />

        {selected.length > 0 && (
          <BulkStrip
            count={selected.length}
            cycles={cycles.data ?? []}
            people={(users.data ?? []).map((user) => ({
              id: user.id,
              displayName: user.displayName,
              avatarUrl: user.avatarUrl,
            }))}
            labels={labels.data ?? []}
            busy={bulkEdit.isPending || bulkDelete.isPending}
            onStatus={(status) => edit({ ticketIds: selected, status })}
            onPriority={(priority) => edit({ ticketIds: selected, priority })}
            onAssign={(userId) => edit({ ticketIds: selected, assigneeIds: userId ? [userId] : [] })}
            onCycle={(cycleId) => edit({ ticketIds: selected, cycleId })}
            onLabel={(labelId) => edit({ ticketIds: selected, labelId })}
            onDelete={() => bulkDelete.mutate(selected, onDone)}
            onCancel={clear}
          />
        )}
      </div>
    </OrganiseShell>
  );
}

/**
 * The strip, plus the two controls only a saved view has.
 *
 * The chips and the `+ Filter` button are `FilterBar`, shared with the main list: the
 * two are one question asked through two doors, and a strip that read differently on
 * each would be the divergence the vocabulary was unified to end.
 */
function Chips({
  view,
  names,
  onAdd,
  onFilters,
  onGroupBy,
  onSortBy,
}: {
  view: SavedView;
  names: ChipNames;
  onAdd: () => void;
  onFilters: (filters: SavedView["filters"]) => void;
  onGroupBy: (groupBy: ViewGroupBy) => void;
  onSortBy: (sortBy: ViewSortBy) => void;
}) {
  return (
    <FilterBar
      filters={view.filters}
      names={names}
      onAdd={onAdd}
      onFilters={onFilters}
      empty={<span className="text-12 text-faint">No filters — everything in the team.</span>}
    >
      {/* `g` and `f` from the shortcut sheet reach these two through the action registry;
          the menus are what makes them discoverable with a mouse. */}
      <Menu
        label="Group by"
        asChild
        trigger={
          <button type="button" id="view-group-by" className="text-12 text-muted-foreground hover:text-foreground">
            Group: {view.groupBy}
          </button>
        }
        items={VIEW_GROUP_BYS.map((groupBy) => ({
          id: `view.groupBy.${groupBy}`,
          label: groupBy,
          onSelect: () => onGroupBy(groupBy),
        }))}
      />
      <Menu
        label="Sort by"
        asChild
        trigger={
          <button type="button" id="view-sort-by" className="text-12 text-muted-foreground hover:text-foreground">
            Sort: {view.sortBy}
          </button>
        }
        items={VIEW_SORT_BYS.map((sortBy) => ({
          id: `view.sortBy.${sortBy}`,
          label: sortBy,
          onSelect: () => onSortBy(sortBy),
        }))}
      />
    </FilterBar>
  );
}

/**
 * The grouped list, virtualised over a **flattened index**: the server answers with
 * groups, `nameGroups` puts a reader's name on each, and `flatten` lays those out as one
 * sequence in which a header is an entry like any other.
 *
 * The alternative — a virtualiser per group — was rejected. It needs a scroller per
 * group, and this screen's cursor walks the whole view: `j` off the bottom of `Todo` and
 * into `In progress` would then have to scroll two elements to stay visible, with the
 * header between them belonging to neither. One sequence means the cursor is one integer,
 * which is what it was before any of this.
 */
function Rows({
  groups,
  groupBy,
  names,
  selected,
  cursor,
  onRow,
}: {
  groups: TicketGroup[];
  groupBy: ViewGroupBy;
  names: { person: (id: string) => string; project: (id: string) => string };
  selected: string[];
  cursor?: string;
  onRow: (id: string) => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const metrics = useRowMetrics(scroller);
  const chosen = new Set(selected);

  const flat = useMemo(
    () => flatten(nameGroups(groups, groupBy, names), (ticket) => ticket.id),
    [groups, groupBy, names],
  );

  const virtualizer = useVirtualizer({
    count: metrics.height > 0 ? flat.length : 0,
    getScrollElement: () => scroller.current,
    // A header is taller than a row, so the two are estimated apart: one number for both
    // would put the scrollbar wrong on every grouped view and make the thumb jump as
    // each header came into view. Rows are exactly `--row-h`, so only the headers are
    // ever re-measured, which is what `measureElement` is attached below for.
    estimateSize: (index) =>
      sizeAt(flat, index, { row: metrics.height + metrics.gap, header: GROUP_LABEL_ESTIMATE }),
    measureElement: (element) => element.getBoundingClientRect().height,
    overscan: 8,
    getItemKey: (index) => flat[index]?.key ?? index,
  });

  /**
   * The cursor, kept on screen — which this list could not do at all before, and could
   * not have gone on not doing: `j` now moves onto rows that have no element, so a
   * cursor nobody scrolls to is a cursor nobody can find.
   *
   * `flatIndexOf` is what makes it answerable. The cursor is a ticket id and the
   * virtualiser wants an integer, and the conversion has to count the headers the list
   * draws between the groups — which is exactly what the flattened index already holds.
   */
  const cursorIndex = flatIndexOf(flat, cursor);
  useEffect(() => {
    if (cursorIndex >= 0) virtualizer.scrollToIndex(cursorIndex, { align: "auto" });
  }, [cursorIndex, virtualizer]);

  return (
    <div ref={scroller} className="min-h-0 flex-1 overflow-y-auto px-6 pb-24">
      <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
        {virtualizer.getVirtualItems().map((item) => {
          const entry = flat[item.index];
          return (
            <div
              key={item.key}
              // Headers are measured, rows are not, so only a header carries the ref —
              // `measureElement` on a fixed-height row is a layout read per scroll for
              // an answer `--row-h` already gave.
              ref={entry.kind === "header" ? virtualizer.measureElement : undefined}
              data-index={item.index}
              className="absolute left-0 top-0 w-full"
              style={{ transform: `translateY(${item.start}px)` }}
            >
              {entry.kind === "header" ? (
                <GroupLabel>
                  {entry.label} · {entry.count}
                </GroupLabel>
              ) : (
                <Row
                  selected={chosen.has(entry.item.id)}
                  data-testid="view-row"
                  data-cursor={entry.item.id === cursor}
                  className="grid grid-cols-[18px_70px_1fr_20px_96px]"
                  onClick={() => onRow(entry.item.id)}
                >
                  <span
                    aria-hidden
                    className={
                      chosen.has(entry.item.id)
                        ? "grid size-3.5 place-items-center rounded-sm bg-primary text-[9px] text-primary-foreground"
                        : "size-3.5 rounded-sm border border-border"
                    }
                  >
                    {chosen.has(entry.item.id) ? "✓" : ""}
                  </span>
                  <span className="font-mono text-11 text-faint">{entry.item.identifier}</span>
                  <span className="truncate">{entry.item.title}</span>
                  <PriorityMark priority={entry.item.priority} />
                  <span
                    className={
                      entry.item.priority === "urgent"
                        ? "truncate text-11 text-urgent"
                        : "truncate text-11 text-muted-foreground"
                    }
                  >
                    {entry.item.priority === "none" ? "" : PRIORITY_LABELS[entry.item.priority]}
                  </span>
                </Row>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
