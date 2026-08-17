"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Menu } from "@/components/menu";
import { GroupLabel } from "@/components/ui/group-label";
import { PriorityMark } from "@/components/ui/priority-mark";
import { Row } from "@/components/ui/row";
import { actionErrorMessage } from "@/lib/errors";
import {
  VIEW_GROUP_BYS,
  VIEW_SORT_BYS,
  type SavedView,
  type Ticket,
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
  useViewTickets,
} from "@/lib/queries";
import { useTeamLabels } from "@/lib/queries/social";
import { PRIORITY_LABELS } from "@/lib/status";
import { BulkStrip } from "./bulk-strip";
import { chipsOf, withoutChip } from "./chips";
import { groupTickets } from "./grouping";
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
  const rows = useViewTickets(id);
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

  const tickets = rows.data ?? [];
  // Keyed on `rows.data`, not on `tickets`: the `?? []` makes a fresh array every render,
  // and memoising against it would recompute every time and rebuild every callback below.
  const ids = useMemo(() => (rows.data ?? []).map((ticket) => ticket.id), [rows.data]);

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

      if (event.key === "Escape") {
        clear();
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
            onFilters={(filters) => patch.mutate({ id, filters })}
            onGroupBy={(groupBy) => patch.mutate({ id, groupBy })}
            onSortBy={(sortBy) => patch.mutate({ id, sortBy })}
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
          tickets={tickets}
          groupBy={view.data?.groupBy ?? "status"}
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

function Chips({
  view,
  names,
  onFilters,
  onGroupBy,
  onSortBy,
}: {
  view: SavedView;
  names: Parameters<typeof chipsOf>[1];
  onFilters: (filters: SavedView["filters"]) => void;
  onGroupBy: (groupBy: ViewGroupBy) => void;
  onSortBy: (sortBy: ViewSortBy) => void;
}) {
  const chips = chipsOf(view.filters, names);

  return (
    <div className="flex flex-wrap items-center gap-2 px-6 pt-[18px] pb-3.5">
      {chips.map((chip) => (
        <span
          key={chip.key}
          data-testid="filter-chip"
          className="inline-flex items-center gap-2 rounded-md border border-border bg-card px-2.5 py-1 text-12 text-muted-foreground"
        >
          {chip.label} {chip.value && <span className="text-foreground">{chip.value}</span>}
          <button
            type="button"
            aria-label={`Remove ${chip.label} filter`}
            className="text-faint hover:text-foreground"
            onClick={() => onFilters(withoutChip(view.filters, chip.key))}
          >
            ×
          </button>
        </span>
      ))}

      {chips.length === 0 && <span className="text-12 text-faint">No filters — everything in the team.</span>}

      <span className="flex-1" />

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
    </div>
  );
}

function Rows({
  tickets,
  groupBy,
  names,
  selected,
  cursor,
  onRow,
}: {
  tickets: Ticket[];
  groupBy: ViewGroupBy;
  names: { person: (id: string) => string; project: (id: string) => string };
  selected: string[];
  cursor?: string;
  onRow: (id: string) => void;
}) {
  const groups = groupTickets(tickets, groupBy, names);
  const chosen = new Set(selected);

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-row overflow-y-auto px-6 pb-24">
      {groups.map((group) => (
        <div key={group.key || "all"}>
          {group.label && (
            <GroupLabel>
              {group.label} · {group.count}
            </GroupLabel>
          )}
          <div className="flex flex-col gap-row">
            {group.tickets.map((ticket) => (
              <Row
                key={ticket.id}
                selected={chosen.has(ticket.id)}
                data-testid="view-row"
                data-cursor={ticket.id === cursor}
                className="grid grid-cols-[18px_70px_1fr_20px_96px]"
                onClick={() => onRow(ticket.id)}
              >
                <span
                  aria-hidden
                  className={
                    chosen.has(ticket.id)
                      ? "grid size-3.5 place-items-center rounded-sm bg-primary text-[9px] text-primary-foreground"
                      : "size-3.5 rounded-sm border border-border"
                  }
                >
                  {chosen.has(ticket.id) ? "✓" : ""}
                </span>
                <span className="font-mono text-11 text-faint">{ticket.identifier}</span>
                <span className="truncate">{ticket.title}</span>
                <PriorityMark priority={ticket.priority} />
                <span
                  className={
                    ticket.priority === "urgent"
                      ? "truncate text-11 text-urgent"
                      : "truncate text-11 text-muted-foreground"
                  }
                >
                  {ticket.priority === "none" ? "" : PRIORITY_LABELS[ticket.priority]}
                </span>
              </Row>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
