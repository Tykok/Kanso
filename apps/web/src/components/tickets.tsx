"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { dayValue, type Ticket } from "@/lib/api";
import type { ActionContext } from "@/lib/actions";
import { Row, rowActionsTriggerClass } from "./ui/row";
import { GroupLabel } from "./ui/group-label";
import { PriorityMark, StatusPill, SyncBadge } from "./pills";
import { Menu } from "./menu";
import { menuItems } from "./menu-items";

/** The list's own grid: id, priority, status, title, project, due, mirror, actions.
 *  Shared between the column header and every row so the two always line up. */
const COLS = "grid-cols-[70px_20px_108px_1fr_112px_60px_64px_24px]";

function ColumnHeader() {
  return (
    <GroupLabel className={`grid ${COLS} items-center gap-3 pt-0`}>
      <span>ID</span>
      <span />
      <span>Status</span>
      <span>Title</span>
      <span>Project</span>
      <span>Due</span>
      <span>Sync</span>
      <span />
    </GroupLabel>
  );
}

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
      data-testid="row-title-input"
      // Tighter than the global input's 6px/8px: this one lives inside a 36px (or,
      // compact, 27px) row rather than a form, and the default padding alone would
      // already be taller than the compact row it has to fit in.
      className="w-full min-w-0 px-1.5 py-[3px]"
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

  const project = ctx.projects.find((candidate) => candidate.id === ticket.projectId);

  return (
    <Row
      ref={rowRef}
      data-testid="ticket-row"
      data-archived={ticket.archived}
      selected={selected}
      className={`grid ${COLS}`}
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
      <span data-testid="row-id" className="font-mono text-11 text-faint">
        {ticket.identifier}
      </span>
      <PriorityMark priority={ticket.priority} ctx={ctx} />
      <StatusPill status={ticket.status} ctx={ctx} />

      {editing ? (
        <TitleEditor initialTitle={ticket.title} onCommit={onRename} onCancel={onCancelEdit} />
      ) : (
        <span
          className={
            ticket.archived
              ? "truncate text-faint line-through"
              : "truncate"
          }
        >
          {ticket.title}
        </span>
      )}

      {project ? (
        <span className="truncate text-12 text-muted-foreground">{project.name}</span>
      ) : (
        <span />
      )}

      {/* `MM-DD`, sliced off the ISO string: a day is never run through a Date. */}
      <span className="text-11 text-faint">{ticket.due ? dayValue(ticket.due).slice(5) : ""}</span>

      <SyncBadge mirror={ticket.mirror} />

      <Menu
        label={`Actions for ${ticket.identifier}`}
        asChild
        trigger={
          <button type="button" className={rowActionsTriggerClass}>
            ⋯
          </button>
        }
        items={menuItems(ctx, ["ticket.rename", "ticket.archive", "ticket.delete"])}
      />
    </Row>
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
  /**
   * What to draw instead of rows when there are none.
   *
   * A prop rather than a component built here: screen 15 tells "the filter found nothing"
   * from "there is nothing" and needs the unfiltered count, the instance-wide count and
   * four callbacks to do it — none of which this component has any other reason to know.
   * The caller holds them all already.
   */
  empty?: ReactNode;
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
  empty,
}: ListProps) {
  if (tickets.length === 0) {
    return (
      empty ?? (
        <div className="empty">
          Nothing here. Press <kbd>c</kbd> to create a ticket.
        </div>
      )
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-2 px-3">
      <ColumnHeader />
      <div className="flex flex-col gap-row">
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
    </div>
  );
}
