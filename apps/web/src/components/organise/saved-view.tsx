"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { GROUP_LABEL_ESTIMATE, GroupLabel } from "@/components/ui/group-label";
import { PriorityMark } from "@/components/ui/priority-mark";
import { Row } from "@/components/ui/row";
import { actionErrorMessage } from "@/lib/errors";
import { type TicketGroup, type ViewGroupBy } from "@/lib/api";
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
import { BulkStrip } from "./bulk-strip";
import { FilterInput } from "./filter-input";
import { nameGroups } from "./grouping";
import { ViewControls } from "./view-controls";
import { FavouriteStar } from "../favourites";
import { ShellAside, TopbarSlot, usePageShell } from "@/components/shell/topbar-slot";
import { usePageActions } from "@/components/shell/use-shell-keys";
import { useActionContext } from "@/lib/use-action-ctx";
import { useOrganiseTeam } from "./team";
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

  /** `⇧↓` / `⇧↑`: the range grows and the cursor follows it, in one keypress. */
  const extendBy = useCallback(
    (delta: number) => {
      setSelected((current) => extend(ids, current, cursor, delta));
      move(delta);
    },
    [ids, cursor, move],
  );

  /**
   * The two gestures this screen owns, and the reason they are claims rather than a
   * `keydown` of their own.
   *
   * All five of screen 21's keys are registry actions now, and this file used to explain
   * at length why three of them could not be. Two of the three obstacles were the
   * registry's: `x` collided with `ticket.archive` in the shared bucket and now sits in a
   * `savedView` mode of its own, and `⇧↑↓` were inexpressible because `event.key` for
   * Shift+ArrowDown is still `"ArrowDown"` and a shortcut held one bare key. The third is
   * this page's and always will be: the selection is local state, so the *body* has to be
   * supplied here even though the *key* belongs in the table with every other key.
   *
   * `Escape` is not among them: it is `app.back`, and this screen's own meaning for it is
   * the `onEscape` claim below — which is what puts clearing a selection *between*
   * "close what is open" and "leave the page", where neither a second window listener nor
   * a registry action could have put it.
   *
   * `n` `p` `↑` `↓` are not here either. They are `ticket.moveDown` and `ticket.moveUp`,
   * the same two actions the list and the board answer, running against the context
   * published below — which is the whole of "next row is written out by hand three times"
   * being written out once.
   */
  usePageActions({
    "organise.select": () => {
      if (cursor !== undefined) setSelected((current) => toggle(current, cursor));
    },
    "organise.selectRangeDown": () => extendBy(1),
    "organise.selectRangeUp": () => extendBy(-1),
  });

  const onDone = { onError: (failure: unknown) => setError(actionErrorMessage(failure)), onSuccess: clear };
  const edit = (change: Parameters<typeof bulkEdit.mutate>[0]) => bulkEdit.mutate(change, onDone);

  /**
   * `Escape` on this screen, and the one page in the app that means something of its own
   * by it: with rows selected it drops the selection and stays. With none it takes the
   * key back, and the shell leaves — which is what it always should have done here and
   * could not, since `OrganiseShell` had no `Escape` at all.
   *
   * A `useCallback`, because the shell publishes it through a dependency list: an inline
   * arrow would be a new function on every render and re-publish on every one of them.
   */
  const onEscape = useCallback(() => {
    if (selected.length === 0) return false;
    clear();
    return true;
  }, [selected.length, clear]);

  /**
   * A context, so that "next row" is core's `ticket.moveDown` here too.
   *
   * The rows and the cursor's step are the only two fields this screen fills, and that is
   * deliberately all: `selected` stays undefined, so `↵`, `e`, `1`–`6` and `x`'s shared
   * meaning are all inert exactly as they were when this route ran on the shell's blank
   * context. Filling `selected` from the cursor would quietly turn six keys on and change
   * what they mean on a screen whose `x` is a checkbox — and no key's meaning changes in
   * this slice except where §6.4 says so.
   *
   * `reportError` goes to this page's own strip rather than the shell's: a failed bulk
   * edit belongs beside the rows it was about, which is where `error` is already drawn.
   */
  const reportError = useCallback(
    (message: string | null) => setError(message),
    [],
  );
  const noop = useCallback(() => {}, []);
  const ctx = useActionContext({
    tickets,
    selected: undefined,
    move,
    startRename: noop,
    startLink: noop,
    startUnlink: noop,
    reportError,
  });

  usePageShell({ ctx, crumbs: { team: team?.name, leaf: view.data?.name }, onEscape });

  return (
    <>
      <TopbarSlot>
        {/* Beside the name, for the reason the document's is: `s` reaches the registry
            here now, but the star is what says which way the press will go. */}
        {view.data && <FavouriteStar target={{ kind: "view", id: id }} label={view.data.name} />}

        {/* §6.6's controls, at the left of the slot. Group and Order, because this is the
            one screen that stores a choice for either: the two menus were down in the chip
            strip until this slice, and they came up here with their ids. Filter is gone
            from the component entirely — the box it focused is on this screen already. */}
        {view.data && (
          <ViewControls
            group={{
              value: view.data.groupBy,
              onChange: (groupBy) => patch.mutate({ id, groupBy }),
            }}
            order={{
              value: view.data.sortBy,
              onChange: (sortBy) => patch.mutate({ id, sortBy }),
            }}
          />
        )}

        <span className="flex-1" />
        {view.data && <span>{view.data.shared ? "Shared with the team" : "Only yours"}</span>}
      </TopbarSlot>

      <ShellAside>
        <ViewRail views={views.data ?? []} currentId={id} />
      </ShellAside>

      <div className="relative flex min-h-0 flex-1 flex-col">
        {view.data && (
          <FilterInput
            filters={view.data.filters}
            teamId={team?.id}
            /**
             * Written straight through to the view, not held and saved on closing. A saved
             * view is a stored question and this *is* the question — every other edit on
             * this screen lands the same way.
             *
             * Which is also why the box asks on `↵` rather than on every keystroke: a line
             * being typed passes through states that parse to no filters at all, and each
             * one of those would be a PATCH that emptied the stored view. `filter-input.tsx`
             * argues it at length.
             */
            onFilters={(filters) => patch.mutate({ id, filters })}
            empty={<span className="text-12 text-faint">No filters — everything in the team.</span>}
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
    </>
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
