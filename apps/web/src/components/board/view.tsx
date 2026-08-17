"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import {
  openTicketMode,
  ticketHref,
  type Ticket,
  type TicketStatus,
} from "@/lib/api";
import { creationSeed } from "@/lib/creation-seed";
import { actionErrorMessage } from "@/lib/errors";
import {
  useCreateTicket,
  usePatchTicket,
  usePreferences,
  useProjects,
  useTeams,
  useTickets,
  useUsers,
} from "@/lib/queries";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";
import { useBoard } from "@/store/board";
import { useUi } from "@/store/ui";
import { StatusDot } from "../ui/status-dot";
import { BoardCard } from "./card";
import { boardColumns } from "./columns";

/**
 * Screen 04 — the board.
 *
 * A third drawing of the scoped tickets query, not a route: `app/page.tsx` reaches it
 * from the same segmented control as the list and the chart, because giving it a URL
 * would mean two ways to be looking at one team's work.
 *
 * The columns are drawn from the same query the list is, filtered the same way, so the
 * cursor `page.tsx` maintains over `visible` is the cursor over these cards — which is
 * what lets `1`–`6` and the row actions work here with nothing registered for the board.
 */
export function BoardView({ reportError }: { reportError: (message: string | null) => void }) {
  const router = useRouter();
  const tickets = useTickets();
  const users = useUsers();
  const teams = useTeams();
  const projects = useProjects();
  const patch = usePatchTicket();
  const preferences = usePreferences();
  const { scope, query, selectedId, select, open } = useUi();
  const { requestedOpen, openHandled } = useBoard();
  const [dragging, setDragging] = useState<string | null>(null);

  /**
   * The same predicate `page.tsx` applies to the list.
   *
   * Restated rather than passed in: the page is frozen for the fan-out and takes only
   * `reportError`, so the alternative to these four lines is a board that ignores the
   * filter box drawn directly above it. The two must not drift — if the list's filter
   * grows a field, this grows the same one.
   */
  const visible = useMemo(() => {
    const rows = tickets.data ?? [];
    const needle = query.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter(
      (ticket) =>
        ticket.title.toLowerCase().includes(needle) ||
        ticket.identifier.toLowerCase().includes(needle),
    );
  }, [tickets.data, query]);

  const columns = useMemo(() => boardColumns(visible), [visible]);

  const nameOf = useMemo(() => {
    const byId = new Map((users.data ?? []).map((person) => [person.id, person.displayName]));
    return (ticket: Ticket) => byId.get(ticket.assigneeIds[0] ?? "");
  }, [users.data]);

  /**
   * How a card opens, in one place.
   *
   * `preferences.openTicket` is read here rather than in the action because this is where
   * both halves of the answer already are — the preference, from the query cache, and the
   * router. Screen 02's `⤢` and screen 03 are the page; the panel is everything else.
   */
  const openTicket = useMemo(() => {
    const mode = openTicketMode(preferences);
    return (identifier: string, id: string) => {
      select(id);
      if (mode === "page") router.push(ticketHref(identifier));
      else open("detail");
    };
  }, [preferences, router, select, open]);

  /**
   * `↵` asked for a card. The key cannot read a preference or reach a router, so it
   * writes the identifier here and this answers it. Cleared immediately, so the same card
   * opened twice in a row fires twice.
   */
  useEffect(() => {
    if (!requestedOpen) return;
    const wanted = visible.find((ticket) => ticket.identifier === requestedOpen);
    openHandled();
    if (wanted) openTicket(wanted.identifier, wanted.id);
  }, [requestedOpen, visible, openHandled, openTicket]);

  const moveTo = (status: TicketStatus, ticketId: string) => {
    const ticket = visible.find((candidate) => candidate.id === ticketId);
    // A drop back onto the column a card came from is not an edit, and sending it would
    // mark the mirror pending for a change nobody made.
    if (!ticket || ticket.status === status) return;
    patch.mutate(
      { id: ticketId, status },
      {
        // The patch is optimistic, so a refusal already shows as the card snapping back;
        // the strip says *why*, which a snap-back cannot.
        onError: (error) => reportError(actionErrorMessage(error)),
        onSuccess: () => reportError(null),
      },
    );
  };

  if (tickets.error) return <div className="empty error">{(tickets.error as Error).message}</div>;
  if (visible.length === 0) {
    return (
      <div className="empty">
        Nothing here. Press <kbd>c</kbd> to create a ticket.
      </div>
    );
  }

  // Empty when the scope names no team a ticket could be filed in — `creationSeed`'s own
  // rule, and the reason the per-column composer disappears rather than failing.
  const seed = creationSeed(scope, teams.data ?? [], projects.data ?? []);

  return (
    <div
      data-testid="board"
      // Six equal columns that stop shrinking at 180px and scroll sideways instead:
      // `grid-cols-6` alone is `minmax(0, 1fr)`, and a 12px title in a 90px column wraps
      // to five lines. The floor is what makes the board readable on a laptop.
      className="grid min-h-0 flex-1 grid-cols-[repeat(6,minmax(180px,1fr))] gap-2.5 overflow-auto p-4"
    >
      {columns.map((column) => (
        <section
          key={column.status}
          data-testid="board-column"
          data-status={column.status}
          aria-label={`${STATUS_LABELS[column.status]}, ${column.tickets.length}`}
          className="flex min-w-0 flex-col gap-2"
          onDragOver={(event) => {
            // Without this the browser refuses the drop and the card springs back with no
            // explanation — the default for a region that has not said it accepts one.
            event.preventDefault();
            event.dataTransfer.dropEffect = "move";
          }}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(null);
            const id = event.dataTransfer.getData("text/plain");
            if (id) moveTo(column.status, id);
          }}
        >
          <header
            className="flex items-center gap-[7px] rounded-md px-2 py-1.5"
            // The hue at 12%, so the tint is a wash of the column's own colour rather
            // than six new tokens — and `--status-backlog` and `--status-canceled` are
            // already neutral, which is why those two headers come out plain, exactly as
            // the drawing has them.
            style={{ background: `color-mix(in oklch, ${STATUS_COLORS[column.status]} 12%, transparent)` }}
          >
            <StatusDot status={column.status} />
            <span className="min-w-0 flex-1 truncate text-12 font-medium">
              {STATUS_LABELS[column.status]}
            </span>
            <span className="font-mono text-11 text-faint">{column.tickets.length}</span>
          </header>

          {column.tickets.map((ticket) => (
            <BoardCard
              key={ticket.id}
              ticket={ticket}
              assignee={nameOf(ticket)}
              selected={ticket.id === selectedId}
              onSelect={() => select(ticket.id)}
              onOpen={() => openTicket(ticket.identifier, ticket.id)}
              onDragStart={() => setDragging(ticket.id)}
            />
          ))}

          {dragging && !column.tickets.some((ticket) => ticket.id === dragging) && (
            <div className="h-8 rounded-md border border-dashed border-border" aria-hidden />
          )}

          {!seed.ticket.blocked && (
            <ColumnComposer
              status={column.status}
              teamId={seed.ticket.teamId}
              projectId={seed.ticket.projectId}
            />
          )}
        </section>
      ))}
    </div>
  );
}

/**
 * The column's own `+ Add`.
 *
 * A one-line input rather than the shared composer, because the whole point of adding
 * from a column is that the column *is* the status — and `Composer` takes a scope and no
 * status, so routing through it would file every card in `todo` whichever column was
 * clicked. Everything else it would have asked for is already answered by the scope.
 */
function ColumnComposer({
  status,
  teamId,
  projectId,
}: {
  status: TicketStatus;
  teamId: string;
  projectId: string;
}) {
  const create = useCreateTicket();
  const [title, setTitle] = useState<string | null>(null);

  if (title === null) {
    return (
      <button
        type="button"
        className="px-2 py-1.5 text-left text-11 text-faint hover:text-foreground"
        onClick={() => setTitle("")}
      >
        + Add
      </button>
    );
  }

  const submit = () => {
    const next = title.trim();
    setTitle(null);
    if (!next) return;
    create.mutate({ teamId, title: next, status, ...(projectId ? { projectId } : {}) });
  };

  return (
    <input
      autoFocus
      aria-label={`New ticket in ${STATUS_LABELS[status]}`}
      className="w-full rounded-md border border-border bg-card px-2 py-1.5 text-12"
      value={title}
      onChange={(event) => setTitle(event.target.value)}
      onBlur={submit}
      onKeyDown={(event) => {
        // The page answers bare keys on `window`; a title being typed must not also be
        // moving the cursor or changing a status.
        event.stopPropagation();
        if (event.key === "Enter") submit();
        if (event.key === "Escape") setTitle(null);
      }}
    />
  );
}
