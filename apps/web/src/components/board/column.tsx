"use client";

import { useEffect, useRef, useState, type RefObject } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useCreateTicket } from "@/lib/queries";
import { colourOf } from "@/lib/status";
import { ticketAddress, type Ticket, type TicketStatus } from "@/lib/api";
import { StatusDot } from "../ui/status-dot";
import { BoardCard } from "./card";
import type { BoardColumn } from "./columns";

/** What a column needs from the board to make the cards in it answer the pointer. */
export type ColumnControl = {
  selectedId?: string;
  /** The first assignee's display name, resolved once by the board rather than per card. */
  nameOf: (ticket: Ticket) => string | undefined;
  onSelect: (ticketId: string) => void;
  onOpen: (identifier: string, ticketId: string) => void;
  onDrop: (status: TicketStatus, ticketId: string) => void;
  onDragStart: (ticketId: string) => void;
  onDragEnd: () => void;
};

/**
 * One column: its header, its cards, and its own `+ Add`.
 *
 * **Each column scrolls itself**, which is the one thing virtualising the board changed
 * about how it behaves. Sharing the board's single scroller was tried first, since it
 * kept the board a single surface, and it does not work: a card's height is whatever its
 * title wraps to, so the six virtualisers all measure their cards and all correct the
 * scroll position to keep what is on screen from jumping — on the same element. Those
 * corrections compound, and the board runs itself to the bottom the moment two columns
 * re-measure in one frame. Six scrollers is not a workaround for that; it is the
 * arrangement in which each virtualiser owns a scroll position, which is what every one
 * of them assumes.
 *
 * What the reader gets for it: the six headers, and the count on each, stay put instead
 * of scrolling away from the cards they are counting. Sideways the board still scrolls as
 * one, which is the axis the columns share.
 */
export function BoardColumnView({
  column,
  board,
  control,
  seed,
  dragging,
}: {
  column: BoardColumn;
  /** The board's horizontal scroller, for bringing an off-screen column back sideways. */
  board: RefObject<HTMLDivElement | null>;
  control: ColumnControl;
  /** Where a card created here would be filed, or nothing when no team can hold one. */
  seed?: { teamId: string; projectId: string };
  /** The card currently under the hand, from anywhere on the board. */
  dragging: string | null;
}) {
  const section = useRef<HTMLElement>(null);
  const scroller = useRef<HTMLDivElement>(null);

  const virtualizer = useVirtualizer({
    count: column.tickets.length,
    getScrollElement: () => scroller.current,
    /**
     * A card is as tall as its title wraps, plus a strip that is only drawn when there
     * is something to put in it — so unlike a list row there is no height to read off a
     * token. This is the first guess; `measureElement` replaces it with the real height
     * the moment the card is drawn.
     */
    estimateSize: () => 84,
    measureElement: (element) => element.getBoundingClientRect().height,
    overscan: 6,
    getItemKey: (index) => column.tickets[index]?.id ?? index,
  });

  /**
   * The cursor, kept on screen.
   *
   * A card below the fold has no element, so it can no longer scroll itself into view
   * the way it did before this column was virtualised — `j` down a long column, or `1`–`6`
   * moving a card into one, would leave the cursor somewhere nobody could see. So the
   * column scrolls to the index instead, which is answerable for every card it holds.
   *
   * Sideways is done by hand rather than with `scrollIntoView`. `h` and `l` move the
   * cursor into a column that may be off the right edge, and the only call that would
   * bring it back — `scrollIntoView({ inline: "nearest" })` on the section — cannot be
   * asked to leave the vertical scroll alone: `block: "nearest"` on an element taller
   * than the viewport still scrolls to it. Two axes, two answers.
   */
  const row = control.selectedId
    ? column.tickets.findIndex((ticket) => ticket.id === control.selectedId)
    : -1;
  useEffect(() => {
    if (row < 0) return;
    virtualizer.scrollToIndex(row, { align: "auto" });

    const across = board.current;
    const element = section.current;
    if (!across || !element) return;
    const left = element.offsetLeft;
    const right = left + element.offsetWidth;
    if (left < across.scrollLeft) across.scrollLeft = left;
    else if (right > across.scrollLeft + across.clientWidth) {
      across.scrollLeft = right - across.clientWidth;
    }
  }, [row, virtualizer, board]);

  return (
    <section
      ref={section}
      data-testid="board-column"
      data-status={column.status}
      aria-label={`${column.label}, ${column.tickets.length}`}
      className="flex min-h-0 min-w-0 flex-col gap-2"
      onDragOver={(event) => {
        // Without this the browser refuses the drop and the card springs back with no
        // explanation — the default for a region that has not said it accepts one.
        event.preventDefault();
        event.dataTransfer.dropEffect = "move";
      }}
      onDrop={(event) => {
        event.preventDefault();
        control.onDragEnd();
        const id = event.dataTransfer.getData("text/plain");
        if (id) control.onDrop(column.status, id);
      }}
    >
      <header
        className="flex items-center gap-[7px] rounded-md px-2 py-1.5"
        // The hue at 12%, so the tint is a wash of the column's own colour rather than
        // six new tokens — and `--status-backlog` and `--status-canceled` are already
        // neutral, which is why those two headers come out plain, exactly as the drawing
        // has them.
        style={{ background: `color-mix(in oklch, ${colourOf(column.status, column.category)} 12%, transparent)` }}
      >
        <StatusDot status={column.status} category={column.category} />
        {/*
         * The column's own word, carried on it since `KAN-90` — not a lookup. Both keys a
         * board can be stacked by are words Kanso does not ship: a team's own status, and a
         * category on a board spanning teams. A table of the six answers either with the
         * raw key, so the header would read `devis` and `started`.
         */}
        <span className="min-w-0 flex-1 truncate text-12 font-medium">{column.label}</span>
        <span className="font-mono text-11 text-faint">{column.tickets.length}</span>
      </header>

      {/*
       * The column's own scroller. The placeholder and the `+ Add` are inside it so they
       * stay where they have always been — at the end of the cards — rather than pinned
       * under a list that scrolls past them.
       */}
      <div ref={scroller} className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto">
        {/* As tall as every card in the column, so the scrollbar measures the work rather
            than the fraction of it that happens to be mounted. */}
        <div className="relative w-full shrink-0" style={{ height: virtualizer.getTotalSize() }}>
          {virtualizer.getVirtualItems().map((item) => {
            const ticket = column.tickets[item.index];
            return (
              <div
                key={item.key}
                ref={virtualizer.measureElement}
                data-index={item.index}
                className="absolute left-0 top-0 w-full"
                style={{ transform: `translateY(${item.start}px)` }}
              >
                {/* The gap the column used to get from `gap-2`, which absolute
                    positioning takes away. Inside the measured element, so the height
                    the virtualiser records is the space the card actually occupies. */}
                <div className="pb-2">
                  <BoardCard
                    ticket={ticket}
                    assignee={control.nameOf(ticket)}
                    selected={ticket.id === control.selectedId}
                    onSelect={() => control.onSelect(ticket.id)}
                    onOpen={() => control.onOpen(ticketAddress(ticket), ticket.id)}
                    onDragStart={() => control.onDragStart(ticket.id)}
                  />
                </div>
              </div>
            );
          })}
        </div>

        {dragging && !column.tickets.some((ticket) => ticket.id === dragging) && (
          <div className="h-8 shrink-0 rounded-md border border-dashed border-border" aria-hidden />
        )}

        {seed && (
          <ColumnComposer
            status={column.status}
            label={column.label}
            teamId={seed.teamId}
            projectId={seed.projectId}
          />
        )}
      </div>
    </section>
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
  label,
  teamId,
  projectId,
}: {
  status: TicketStatus;
  /** The column's own word, for the same reason its header carries one. */
  label: string;
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
      aria-label={`New ticket in ${label}`}
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
