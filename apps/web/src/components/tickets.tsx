"use client";

import { useEffect, useRef, useState } from "react";
import type { Ticket } from "@/lib/api";
import type { ActionContext } from "@/lib/actions";
import { PriorityMark, StatusPill, SyncBadge } from "./pills";
import { Menu } from "./menu";
import { menuItems } from "./menu-items";

type RowProps = {
  ticket: Ticket;
  selected: boolean;
  editing: boolean;
  ctx: ActionContext;
  onSelect: () => void;
  onOpen: () => void;
  onRename: (title: string) => void;
  onCancelEdit: () => void;
};

/**
 * Its own component so the draft starts from the title on mount and nothing has to
 * reset it afterwards. It also means a rename arriving over the WebSocket while
 * someone is typing no longer wipes what they were writing.
 */
function TitleEditor({
  initialTitle,
  onCommit,
  onCancel,
}: {
  initialTitle: string;
  onCommit: (title: string) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState(initialTitle);

  const commit = () => {
    const next = draft.trim();
    if (next && next !== initialTitle) onCommit(next);
    else onCancel();
  };

  return (
    <input
      className="row-title-input"
      autoFocus
      value={draft}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === "Enter") {
          event.preventDefault();
          commit();
        }
        if (event.key === "Escape") {
          event.preventDefault();
          onCancel();
        }
        event.stopPropagation();
      }}
    />
  );
}

function TicketRow({ ticket, selected, editing, ctx, onSelect, onOpen, onRename, onCancelEdit }: RowProps) {
  const rowRef = useRef<HTMLDivElement>(null);

  // Keep the cursor on screen when it moves by keyboard rather than by wheel.
  useEffect(() => {
    if (selected) rowRef.current?.scrollIntoView({ block: "nearest" });
  }, [selected]);

  return (
    <div
      ref={rowRef}
      className="row"
      data-selected={selected}
      data-archived={ticket.archived}
      onClick={onSelect}
      onDoubleClick={(event) => {
        // `dblclick` is its own native event, dispatched and bubbled independently of
        // `click` — the menu trigger's `stopPropagation()` on click never touches it.
        // Without this guard, double-clicking the row's `⋯` (or the status/priority
        // pill, whose trigger covers the whole pill) would still reach here and open
        // the ticket.
        if ((event.target as Element).closest(".menu")) return;
        onOpen();
      }}
    >
      <span className="row-id">{ticket.identifier}</span>
      <PriorityMark priority={ticket.priority} ctx={ctx} />
      <StatusPill status={ticket.status} ctx={ctx} />

      {editing ? (
        <TitleEditor initialTitle={ticket.title} onCommit={onRename} onCancel={onCancelEdit} />
      ) : (
        <span className="row-title">{ticket.title}</span>
      )}

      <span className="row-meta">
        {ticket.dueDate && <span title="Due date">{ticket.dueDate.slice(5)}</span>}
        {ticket.assigneeIds.length > 0 && <span title="Assignees">{ticket.assigneeIds.length}👤</span>}
        <SyncBadge mirror={ticket.mirror} />
        <Menu
          label={`Actions for ${ticket.identifier}`}
          items={menuItems(ctx, ["ticket.rename", "ticket.archive", "ticket.delete"])}
        />
      </span>
    </div>
  );
}

type ListProps = {
  tickets: Ticket[];
  selectedId?: string;
  editingId?: string;
  ctx: ActionContext;
  onSelect: (id: string) => void;
  onOpen: (id: string) => void;
  onRename: (id: string, title: string) => void;
  onCancelEdit: () => void;
};

export function TicketList({
  tickets,
  selectedId,
  editingId,
  ctx,
  onSelect,
  onOpen,
  onRename,
  onCancelEdit,
}: ListProps) {
  if (tickets.length === 0) {
    return (
      <div className="empty">
        Nothing here. Press <kbd>c</kbd> to create a ticket.
      </div>
    );
  }

  return (
    <div className="list">
      {tickets.map((ticket) => {
        const rowCtx: ActionContext = { ...ctx, selected: ticket };
        return (
          <TicketRow
            key={ticket.id}
            ticket={ticket}
            selected={ticket.id === selectedId}
            editing={ticket.id === editingId}
            ctx={rowCtx}
            onSelect={() => onSelect(ticket.id)}
            onOpen={() => onOpen(ticket.id)}
            onRename={(title) => onRename(ticket.id, title)}
            onCancelEdit={onCancelEdit}
          />
        );
      })}
    </div>
  );
}
