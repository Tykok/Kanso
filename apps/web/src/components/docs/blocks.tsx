"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { DocBlock, DocBlockContent, Ticket } from "@/lib/api";
import { STATUS_LABELS } from "@/lib/status";
import { cn } from "@/lib/utils";
import { StatusDot } from "../ui/status-dot";

/**
 * One block, drawn and editable in place.
 *
 * Every kind is a `<textarea>` or a `<span>` over the same `content` map, so adding the
 * eighth kind the drawing does not have would be one case here and one value in `V9`'s
 * `CHECK` — and nothing else. What none of them is, is a rich-text model: the drawing
 * shows plain runs with one inline chip (a ticket link), and the whole reason a ticket
 * link is its own *block* is that a chip inside a run of text would need one.
 */

/** Grows with what is typed, so a block is never a scrollable box inside a document. */
function Autosize({
  value,
  onCommit,
  className,
  placeholder,
  onFocus,
  onBlur,
  onKeyDown,
  readOnly,
}: {
  value: string;
  onCommit: (next: string) => void;
  className?: string;
  placeholder?: string;
  onFocus?: () => void;
  onBlur?: () => void;
  onKeyDown?: (event: React.KeyboardEvent<HTMLTextAreaElement>) => void;
  /**
   * Somebody else is holding this block — `KAN-25`.
   *
   * `readOnly` and **not** `disabled`, which is the whole accessibility of the refusal: a
   * disabled textarea cannot be focused, so it is skipped by the tab order and its label
   * is not announced, and a reader working by keyboard would find the paragraph simply
   * absent. Read-only keeps it focusable, selectable and copyable — which is what somebody
   * waiting for a block actually wants to do with it — and refuses only the typing.
   */
  readOnly?: boolean;
}) {
  const [draft, setDraft] = useState(value);
  const ref = useRef<HTMLTextAreaElement>(null);
  const committed = useRef(value);

  /**
   * A refetch that arrives while nobody is typing should show the new text; one that
   * arrives mid-sentence must not. `committed` is the last value this block sent or
   * received, so a server value that differs from it is somebody else's edit.
   *
   * What decides is an **unsaved draft**, not focus, and `KAN-25`'s two-browser test is
   * what corrected that. The guard was `document.activeElement !== ref.current`, which is
   * a good proxy for "somebody is typing here" right up until two people are on the page:
   * a reader who clicks into a paragraph to read it, or to copy a line out of it, has a
   * caret in it and nothing to lose — and under the old rule that paragraph froze at
   * whatever it said the moment they clicked. That is the one case this whole feature
   * exists for: watching somebody else write.
   *
   * `draft !== committed.current` is the honest question. It is true only when this reader
   * has typed something the server has not been told about, which is exactly what must not
   * be overwritten, and it is false for a caret merely resting. It also subsumes the
   * read-only case for free: a `readonly` textarea fires no `onChange`, so its draft can
   * never diverge, so a block somebody else is holding always shows their latest word.
   */
  useEffect(() => {
    if (value === committed.current) return;
    if (draft !== committed.current && document.activeElement === ref.current) return;
    committed.current = value;
    setDraft(value);
  }, [value, draft]);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    node.style.height = "0px";
    node.style.height = `${node.scrollHeight}px`;
  }, [draft]);

  return (
    <textarea
      ref={ref}
      rows={1}
      value={draft}
      placeholder={placeholder}
      readOnly={readOnly}
      // Not `aria-disabled`: the field is genuinely readable and genuinely not writable,
      // which is exactly what `readonly` means to a screen reader. The name of whoever is
      // holding it is on the badge beside this, which the row labels.
      aria-readonly={readOnly || undefined}
      className={cn(
        "w-full resize-none overflow-hidden border-none bg-transparent p-0 text-15 leading-[1.8] text-foreground",
        // Dimmed rather than struck through or greyed to unreadable: the point of a
        // read-only block is that somebody is *reading* it while they wait.
        readOnly && "opacity-60",
        className,
      )}
      onChange={(event) => setDraft(event.target.value)}
      onFocus={onFocus}
      onBlur={() => {
        onBlur?.();
        // Ordered so the lock is released even when there is nothing to commit. The early
        // return below is the common case — a caret that landed in a block and moved on
        // without typing — and it used to be the only path out of this handler.
        if (draft === committed.current) return;
        committed.current = draft;
        onCommit(draft);
      }}
      // The shell dispatches bare keys as actions, and every character of a document is
      // a bare key. Nothing typed here is allowed to reach it.
      onKeyDown={(event) => {
        onKeyDown?.(event);
        event.stopPropagation();
      }}
    />
  );
}

/** The chip a ticket link draws: the identifier, and the ticket's *live* status. */
function TicketChip({ ticket }: { ticket: Ticket }) {
  return (
    <Link
      href={`/t/${ticket.identifier}`}
      data-testid="doc-ticket-chip"
      className="inline-flex h-5 items-center gap-1.5 rounded-sm bg-accent-soft px-[7px] font-mono text-12 text-accent-ink"
      title={`${ticket.title} — ${STATUS_LABELS[ticket.status]}`}
    >
      <StatusDot status={ticket.status} />
      {ticket.identifier}
    </Link>
  );
}

const text = (content: DocBlockContent) => String(content.text ?? "");
const rows = (content: DocBlockContent) =>
  (Array.isArray(content.rows) ? content.rows : []) as string[][];
const columns = (content: DocBlockContent) =>
  (Array.isArray(content.columns) ? content.columns : []).map(String);
const items = (content: DocBlockContent) =>
  (Array.isArray(content.items) ? content.items : []).map(String);

export type BlockProps = {
  block: DocBlock;
  /** Every ticket the page links, so a ticket link block needs no query of its own. */
  tickets: Ticket[];
  /**
   * The page's team's open tickets, for a table block carrying a `query` — the cycle
   * note template's "ticket query included". Empty when no block on the page asks for
   * one, so a document of plain prose costs no extra request.
   */
  teamTickets: Ticket[];
  onCommit: (content: DocBlockContent) => void;
  onFocus: () => void;
  /** The caret left this block: `KAN-25`'s cue to let the lock go. */
  onBlur: () => void;
  onKeyDown: (event: React.KeyboardEvent<HTMLTextAreaElement>) => void;
  /**
   * Somebody else is holding this block.
   *
   * Every writable control in every kind reads this, not just the textarea: a checkbox
   * ticked on a locked block is `updateBlock` with a different payload, and the server
   * refuses it identically. Letting it be clicked would mean the refusal arrived as a
   * red line after the box had visibly ticked itself and then untickled on the refetch,
   * which is worse than the box being unclickable with a name beside it.
   */
  locked?: boolean;
};

/**
 * The statuses a table block's `query` asks for, if it carries one.
 *
 * A filter stored in the block rather than an eighth block kind: the drawn vocabulary is
 * seven, and a table whose rows come from a filter is still a table. It is resolved on
 * every render from the tickets already in hand — a stored result is a document that has
 * to be re-read, which is the thing this whole screen exists not to be.
 */
export const queriedStatuses = (content: DocBlockContent): string[] | undefined => {
  const query = content.query as { status?: unknown } | undefined;
  return Array.isArray(query?.status) ? query.status.map(String) : undefined;
};

export function BlockBody({
  block,
  tickets,
  teamTickets,
  onCommit,
  onFocus,
  onBlur,
  onKeyDown,
  locked,
}: BlockProps) {
  // One object spread into every `Autosize` in every kind, which is what makes the lock
  // reach all seven of them without seven chances to forget one.
  const editable = { onFocus, onBlur, onKeyDown, readOnly: locked };
  const commitText = (next: string) => onCommit({ ...block.content, text: next });

  switch (block.kind) {
    case "heading":
      return (
        <Autosize
          {...editable}
          value={text(block.content)}
          onCommit={commitText}
          placeholder="Heading"
          className="text-[19px] font-medium tracking-[-0.01em]"
        />
      );

    case "checkbox":
      return (
        <label className="flex items-start gap-3">
          <input
            type="checkbox"
            aria-label={text(block.content) || "Checkbox"}
            className="mt-1.5 size-3.5 shrink-0"
            disabled={locked}
            checked={block.content.checked === true}
            onChange={(event) =>
              onCommit({ ...block.content, checked: event.target.checked })
            }
          />
          <Autosize
            {...editable}
            value={text(block.content)}
            onCommit={commitText}
            placeholder="To do"
            className={cn(block.content.checked === true && "text-faint line-through")}
          />
        </label>
      );

    case "callout":
      return (
        <div className="flex gap-3 rounded-lg bg-accent-soft px-4 py-3.5">
          <span aria-hidden className="text-13 leading-normal text-primary">
            ◈
          </span>
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <Autosize
              {...editable}
              value={String(block.content.title ?? "")}
              onCommit={(next) => onCommit({ ...block.content, title: next })}
              placeholder="Callout"
              className="text-13 font-medium"
            />
            <Autosize
              {...editable}
              value={text(block.content)}
              onCommit={commitText}
              className="text-13 text-muted-foreground"
            />
          </div>
        </div>
      );

    case "numbered_list":
      return (
        <ol className="flex flex-col gap-2.5">
          {items(block.content).map((item, index) => (
            <li key={index} className="flex gap-3">
              <span className="text-faint">{index + 1}.</span>
              <Autosize
                {...editable}
                value={item}
                onCommit={(next) => {
                  const nextItems = [...items(block.content)];
                  nextItems[index] = next;
                  onCommit({ ...block.content, items: nextItems });
                }}
              />
            </li>
          ))}
          <li>
            <button
              className="text-12 text-faint hover:text-foreground disabled:opacity-40 disabled:hover:text-faint"
              disabled={locked}
              onClick={() => onCommit({ ...block.content, items: [...items(block.content), ""] })}
            >
              + one more
            </button>
          </li>
        </ol>
      );

    case "ticket_link": {
      // The block stores no title and no status — only the id, in `doc_block_tickets`.
      // Whatever is drawn here came from the ticket a moment ago, which is the entire
      // point: the document does not have to be re-read to still be true.
      const linked = tickets.filter((ticket) => block.ticketIds.includes(ticket.id));
      if (linked.length === 0) {
        return <span className="text-13 text-faint">This ticket no longer exists.</span>;
      }
      return (
        <div className="flex flex-wrap items-center gap-2 text-15">
          {linked.map((ticket) => (
            <span key={ticket.id} className="inline-flex items-center gap-2">
              <TicketChip ticket={ticket} />
              <span className="text-muted-foreground">{ticket.title}</span>
            </span>
          ))}
        </div>
      );
    }

    case "table": {
      const statuses = queriedStatuses(block.content);
      const queried = statuses
        ? teamTickets.filter((ticket) => statuses.includes(ticket.status))
        : [];
      return (
        <div className="overflow-x-auto rounded-lg bg-card shadow-flat">
          <table className="w-full border-collapse text-13">
            <thead>
              <tr>
                {columns(block.content).map((column) => (
                  <th
                    key={column}
                    className="bg-accent px-3.5 py-2.5 text-left text-11 font-medium tracking-[0.1em] text-faint uppercase"
                  >
                    {column}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows(block.content).map((row, rowIndex) => (
                <tr key={rowIndex}>
                  {columns(block.content).map((column, cellIndex) => (
                    <td key={column} className="px-3.5 py-2.5 align-top">
                      <Autosize
                        {...editable}
                        value={String(row[cellIndex] ?? "")}
                        onCommit={(next) => {
                          const nextRows = rows(block.content).map((existing) => [...existing]);
                          nextRows[rowIndex][cellIndex] = next;
                          onCommit({ ...block.content, rows: nextRows });
                        }}
                        className="text-13 leading-normal"
                      />
                    </td>
                  ))}
                </tr>
              ))}
              {/* The queried rows are read-only: they are the tickets, not a copy. */}
              {queried.map((ticket) => (
                <tr key={ticket.id} data-testid="doc-query-row">
                  <td className="px-3.5 py-2.5">
                    <span className="inline-flex items-center gap-2">
                      <TicketChip ticket={ticket} />
                      <span className="text-muted-foreground">{ticket.title}</span>
                    </span>
                  </td>
                  <td className="px-3.5 py-2.5 text-muted-foreground">
                    {STATUS_LABELS[ticket.status]}
                  </td>
                </tr>
              ))}
              {statuses && queried.length === 0 && (
                <tr>
                  <td colSpan={Math.max(columns(block.content).length, 1)} className="px-3.5 py-2.5 text-faint">
                    No ticket in this team matches the query yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      );
    }

    default:
      return (
        <Autosize
          {...editable}
          value={text(block.content)}
          onCommit={commitText}
          placeholder="Write, or press / for a block"
        />
      );
  }
}
