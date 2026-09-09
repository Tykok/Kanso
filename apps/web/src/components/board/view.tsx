"use client";

import { boardShape } from "@/lib/statuses";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { openTicketMode, ticketAddress, ticketHref, type Ticket } from "@/lib/api";
import { creationSeed } from "@/lib/creation-seed";
import { actionErrorMessage } from "@/lib/errors";
import {
  usePatchTicket,
  usePreferences,
  useProjects,
  useTeams,
  useTickets,
  useUsers,
} from "@/lib/queries";
import { useBoard } from "@/store/board";
import { useUi } from "@/store/ui";
import { BoardColumnView, type ColumnControl } from "./column";
import { boardColumns, boardDrop } from "./columns";

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
  /** The board sideways. Each column scrolls its own cards; this is the axis they share. */
  const scroller = useRef<HTMLDivElement>(null);

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
        (ticket.identifier?.toLowerCase().includes(needle) ?? false),
    );
  }, [tickets.data, query]);

  // The columns, how a card finds one, and what a drop into one writes — one answer from
  // one place, because `actions/board.ts` walks the same columns and the two must not
  // drift. A scope naming one team stacks by its catalogue; anything wider stacks by
  // category and rebases the drop onto the card's own team's word.
  const shape = useMemo(() => boardShape(teams.data ?? [], scope), [teams.data, scope]);
  const columns = useMemo(
    () => boardColumns(shape.vocabulary, visible, shape.bucketOf),
    [shape, visible],
  );

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
    return (address: string, id: string) => {
      select(id);
      if (mode === "page") router.push(ticketHref(address));
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
    const wanted = visible.find((ticket) => ticketAddress(ticket) === requestedOpen);
    openHandled();
    if (wanted) openTicket(ticketAddress(wanted), wanted.id);
  }, [requestedOpen, visible, openHandled, openTicket]);

  const moveTo = (columnKey: string, ticketId: string) => {
    const ticket = visible.find((candidate) => candidate.id === ticketId);
    if (!ticket) return;
    // Three answers, and `boardDrop` picks between them: nothing to do, a status to write,
    // or a column this card's team has no word for. The last is a refusal the server would
    // also give, said before the round trip rather than after it.
    const drop = boardDrop(shape, ticket, columnKey);
    if (!drop) return;
    if ("refusal" in drop) {
      reportError(drop.refusal);
      return;
    }
    patch.mutate(
      { id: ticketId, status: drop.status },
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

  const control: ColumnControl = {
    selectedId,
    nameOf,
    onSelect: select,
    onOpen: openTicket,
    onDrop: moveTo,
    onDragStart: setDragging,
    onDragEnd: () => setDragging(null),
  };

  return (
    <div
      ref={scroller}
      data-testid="board"
      // Sideways only, and one row exactly as tall as the board: each column scrolls its
      // own cards now (see `column.tsx` for why), so a vertical scrollbar here as well
      // would be a second one saying something different about the same work. The
      // `minmax(0, 1fr)` row is what lets a column be shorter than its contents at all —
      // an `auto` row grows to the tallest column and nothing ever overflows.
      className="grid min-h-0 flex-1 grid-rows-[minmax(0,1fr)] gap-2.5 overflow-x-auto overflow-y-hidden p-4"
      // Equal columns that stop shrinking at 180px and scroll sideways instead:
      // `minmax(0, 1fr)` alone lets a 12px title in a 90px column wrap to five lines, and
      // the floor is what makes the board readable on a laptop.
      //
      // Counted rather than written out, which is why it is a style and not a class — a
      // Tailwind arbitrary value cannot take a number worked out at render. It was
      // `repeat(6, …)` while every board had Kanso's six; since `KAN-90` a team can have
      // four, and four columns in six tracks left two empty ones at the right — a seventh
      // would have wrapped into a second row the single `grid-rows` track never shows.
      style={{ gridTemplateColumns: `repeat(${columns.length}, minmax(180px, 1fr))` }}
    >
      {columns.map((column) => (
        <BoardColumnView
          key={column.status}
          column={column}
          board={scroller}
          control={control}
          // Empty when the scope names no team a ticket could be filed in — which is why
          // the per-column composer disappears rather than failing — and empty when the
          // columns are categories: a composer in one would have to write a status, and
          // `started` is not one. The `+` comes back the moment the scope names a team.
          seed={
            seed.ticket.blocked || scope.kind !== "team"
              ? undefined
              : { teamId: seed.ticket.teamId, projectId: seed.ticket.projectId }
          }
          dragging={dragging}
        />
      ))}
    </div>
  );
}
