"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { dayValue, type Ticket } from "@/lib/api";
import type { ActionContext } from "@/lib/actions";
import { useRowMetrics } from "@/lib/row-metrics";
import { Row, rowActionsTriggerClass } from "./ui/row";
import { GroupLabel } from "./ui/group-label";
import { PriorityMark, StatusPill, SyncBadge, TicketIdentifier } from "./pills";
import { Menu } from "./menu";
import { useMenuItems } from "./menu-items";

/** The list's own grid: id, priority, status, title, project, points, due, mirror,
 *  actions. Shared between the column header and every row so the two always line up. */
const COLS = "grid-cols-[70px_20px_108px_1fr_112px_32px_60px_64px_24px]";

function ColumnHeader() {
  return (
    <GroupLabel className={`grid ${COLS} items-center gap-3 pt-0`}>
      <span>ID</span>
      <span />
      <span>Status</span>
      <span>Title</span>
      <span>Project</span>
      {/* Abbreviated because the column is 32px: the number is one or two digits, and
          `Estimate` would be wider than everything it labels. */}
      <span title="Estimate in points">Pts</span>
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

/**
 * A row no longer scrolls itself into view when the cursor lands on it, the way it did
 * before the list was virtualised. It cannot: `j` onto the four-hundredth row selects a
 * ticket that has no element, so the effect that used to call `scrollIntoView` would
 * never run — the keyboard would quietly stop working past the first screenful, which is
 * a worse failure than a slow list because nothing on screen admits it. The list scrolls
 * the cursor instead, by index, whether or not the row it names is mounted.
 */
function TicketRow({ ticket, selected, editing, ctx, onSelect, onOpen, onRename, onCancelEdit }: RowProps) {
  const project = ctx.projects.find((candidate) => candidate.id === ticket.projectId);
  // A hook, so it is named here rather than called inside the JSX below: `useMenuItems`
  // reads the reader's own bindings, which is what makes the `⋯` menu print the key a
  // remap actually gave `e`.
  const rowActions = useMenuItems(ctx, ["ticket.rename", "ticket.archive", "ticket.delete"]);

  return (
    <Row
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
      <TicketIdentifier
        ticket={ticket}
        className="font-mono text-11 text-faint"
        data-testid="row-id"
      />
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

      {/* Blank, not `0` and not `—`: an unsized ticket has no estimate, and printing a
          zero would make it read as the smallest work on the list rather than the
          unmeasured work it is. */}
      <span className="text-right font-mono text-11 text-faint">{ticket.estimate ?? ""}</span>

      {/* `MM-DD`, sliced off the ISO string: a day is never run through a Date. */}
      <span className="text-11 text-faint">{ticket.due ? dayValue(ticket.due).slice(5) : ""}</span>

      <SyncBadge mirror={ticket.mirror} />

      <Menu
        label={`Actions for ${ticket.identifier ?? ticket.title}`}
        asChild
        trigger={
          <button type="button" className={rowActionsTriggerClass}>
            ⋯
          </button>
        }
        items={rowActions}
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
  const scroller = useRef<HTMLDivElement>(null);
  const metrics = useRowMetrics(scroller);
  /** A row's own height plus the air under it — `gap-row` made into a number. */
  const pitch = metrics.height + metrics.gap;

  const virtualizer = useVirtualizer({
    // Nothing until `--row-h` has been read. A count with a zero row height would ask
    // the virtualiser how many zero-tall rows fit in the viewport, and the measurement
    // lands in a layout effect — before the browser paints — so the pass that draws no
    // rows is never seen.
    count: metrics.height > 0 ? tickets.length : 0,
    getScrollElement: () => scroller.current,
    estimateSize: () => pitch,
    // Every row here is exactly `--row-h` tall, so the estimate is the measurement and
    // no row needs measuring: `measureElement` would be a layout read per row per
    // scroll for an answer already known.
    overscan: 8,
    // Keyed by ticket rather than by index, so a realtime insert above the cursor moves
    // the rows rather than re-labelling them — a row mid-rename must keep its input.
    getItemKey: (index) => tickets[index]?.id ?? index,
  });

  /**
   * The cursor, kept on screen.
   *
   * The one thing virtualising a list can silently break: `j`, `k` and the palette all
   * move the cursor onto rows that may have no element, and the row can no longer scroll
   * itself. So the list resolves the selected ticket to an index — which is answerable
   * for every row in the list, on screen or not — and scrolls to that.
   *
   * `align: "auto"` is `scrollIntoView({ block: "nearest" })` in the virtualiser's own
   * vocabulary: a row already in view is left exactly where it is, so clicking a row
   * never jumps the list under the hand that clicked it.
   */
  const selectedIndex = selectedId ? tickets.findIndex((row) => row.id === selectedId) : -1;
  useEffect(() => {
    if (selectedIndex >= 0) virtualizer.scrollToIndex(selectedIndex, { align: "auto" });
  }, [selectedIndex, virtualizer]);

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
    <div className="flex min-h-0 flex-1 flex-col">
      {/*
       * Outside the scroller now, where it used to be its first child. A virtualiser
       * measures its rows from the top of the element it scrolls, so a header sharing
       * that element has to be subtracted back out of every offset — and a column header
       * that scrolls away from the four hundred rows it is naming was never the useful
       * behaviour anyway. The horizontal padding is repeated here rather than moved out
       * to the wrapper so the scrollbar stays at the outer edge, exactly where it was.
       */}
      <div className="px-3 pt-2">
        <ColumnHeader />
      </div>

      <div ref={scroller} className="min-h-0 flex-1 overflow-y-auto px-3 pb-2">
        {/* The full height of every row there is, so the scrollbar tells the truth about
            how long the list is rather than about how much of it is mounted. */}
        <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
          {virtualizer.getVirtualItems().map((item) => {
            const ticket = tickets[item.index];
            const rowCtx: ActionContext = { ...ctx, selected: ticket };
            return (
              <div
                key={item.key}
                className="absolute left-0 top-0 w-full"
                style={{ height: metrics.height, transform: `translateY(${item.start}px)` }}
              >
                <TicketRow
                  ticket={ticket}
                  selected={ticket.id === selectedId}
                  editing={ticket.id === editingId}
                  ctx={rowCtx}
                  onSelect={() => onSelect(ticket.id)}
                  onOpen={() => onOpen(ticket.id)}
                  onRename={(title) => onRename(ticket.id, title)}
                  onCancelEdit={onCancelEdit}
                />
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
