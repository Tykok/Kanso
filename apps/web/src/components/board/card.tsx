"use client";

import { useEffect, useRef } from "react";
import { dayValue, type Ticket } from "@/lib/api";
import { categoryOf, STATUS_COLORS } from "@/lib/status";
import { cn } from "@/lib/utils";
import { SyncBadge } from "../pills";
import { PriorityMark } from "../ui/priority-mark";
import { Avatar } from "../views/avatar";
import { cardLabel } from "./columns";

/**
 * One card.
 *
 * The colour rule is the drawing's own and it is stated as a constraint rather than a
 * style: "assez pour lire la colonne d'un coup d'œil, pas assez pour noyer le titre".
 * So the status hue appears exactly twice on a board — tinting the column header, and as
 * a 2px rule along the top of each card — and nowhere on the card's fill or its text.
 * A card tinted with its own status would make six columns of six colours and the titles
 * would be the least legible thing on screen.
 *
 * A `<button>`, and single-click selects while double-click opens: the same two gestures
 * a list row already answers, so the board teaches nothing new. The 2px rule is drawn
 * with `border-t` and an inline `borderTopColor` from `STATUS_COLORS` — the same token
 * map `StatusDot` reads, rather than six Tailwind classes that would have to be kept in
 * step with the vocabulary by hand.
 */
export function BoardCard({
  ticket,
  assignee,
  selected,
  onSelect,
  onOpen,
  onDragStart,
}: {
  ticket: Ticket;
  /** The first assignee's display name, resolved once by the view rather than per card. */
  assignee?: string;
  selected: boolean;
  onSelect: () => void;
  onOpen: () => void;
  onDragStart: () => void;
}) {
  const ref = useRef<HTMLButtonElement>(null);

  // Keeps the cursor on screen when it moves by keyboard rather than by wheel — the same
  // contract `TicketRow` holds, and it matters more here: `h` and `l` can move the cursor
  // into a column that is scrolled out of view sideways.
  useEffect(() => {
    if (selected) ref.current?.scrollIntoView({ block: "nearest", inline: "nearest" });
  }, [selected]);

  const due = ticket.due ? dayValue(ticket.due).slice(5) : "";

  return (
    <button
      ref={ref}
      type="button"
      data-testid="board-card"
      data-ticket={ticket.identifier}
      data-selected={selected}
      draggable
      onDragStart={(event) => {
        // The id and not the identifier: the drop handler patches by id, and reading the
        // payload back out of the event is the only thing that survives a drag the
        // pointer started outside React's own state.
        event.dataTransfer.setData("text/plain", ticket.id);
        event.dataTransfer.effectAllowed = "move";
        onDragStart();
      }}
      // `cardLabel` and not the DOM order: read aloud, the card is a title, an identifier
      // and two glyphs with no sentence between them, and the column it sits in — the one
      // thing a sighted reader gets for free — is not in the markup at all.
      aria-label={cardLabel(ticket)}
      aria-current={selected ? "true" : undefined}
      onClick={onSelect}
      onDoubleClick={onOpen}
      className={cn(
        "flex cursor-pointer flex-col gap-2 rounded-md border-t-2 bg-card p-2.5 text-left shadow-flat",
        selected && "ring-1 ring-primary",
      )}
      style={{ borderTopColor: STATUS_COLORS[ticket.status] }}
    >
      <span
        className={cn(
          "text-12 leading-[1.45] text-pretty",
          // Done recedes and canceled is struck through — the two categories that are
          // over, drawn the way the list already draws an archived row.
          categoryOf(ticket.status) === "completed" && "text-muted-foreground",
          categoryOf(ticket.status) === "canceled" && "text-faint line-through",
        )}
      >
        {ticket.title}
      </span>

      {/* Only drawn when there is something in it: an empty strip on every card would
          cost 18px of height six times over for nothing. */}
      {(ticket.mirror.state !== "disabled" || due) && (
        <div className="flex flex-wrap items-center gap-1.5">
          <SyncBadge mirror={ticket.mirror} />
          {due && <span className="text-11 text-faint">{due}</span>}
        </div>
      )}

      <div className="flex items-center gap-1.5">
        <span className="flex-1 font-mono text-11 text-faint">{ticket.identifier}</span>
        <PriorityMark priority={ticket.priority} />
        <Avatar displayName={assignee} size={18} />
      </div>
    </button>
  );
}
