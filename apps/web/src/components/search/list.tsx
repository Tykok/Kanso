"use client";

import type { Project } from "@/lib/api";
import { TicketIdentifier } from "../pills";
import { cn } from "@/lib/utils";
import { GroupLabel } from "../ui/group-label";
import { StatusDot } from "../ui/status-dot";
import {
  highlight,
  notionRefKey,
  pageKey,
  type SearchOutcome,
  type SearchRow,
} from "./results";

/**
 * The result list: four groups drawn as one walkable sequence — tickets, the documents
 * written in Kanso, the Notion pages Kanso only references, and commands.
 *
 * Split out of `command-palette.tsx` so that file holds the decisions — what one query
 * matches, what `↵` and `⇧↵` do, whether the search is on at all — and this one holds the
 * drawing. The palette owns the cursor and passes it down rather than this list keeping a
 * second copy: `↑` and `↓` walk *rows* across the group headings, so the highlight has to
 * be one index and not one per group.
 */
export function SearchList({
  found,
  query,
  active,
  showCommandHeading,
  projects,
  onHover,
  onOpen,
}: {
  found: SearchOutcome;
  query: string;
  /** The highlighted row, by identity rather than by index — see the note above. */
  active?: SearchRow;
  /**
   * False in the picker, where the rows are the only thing on screen and a "Commands"
   * heading over a list of tickets would name the wrong thing.
   */
  showCommandHeading: boolean;
  projects: Project[];
  onHover: (index: number) => void;
  onOpen: (row: SearchRow, inPage: boolean) => void;
}) {
  const { rows } = found;
  const indexOf = (key: string) => rows.findIndex((row) => row.key === key);

  return (
    <div className="flex max-h-[60vh] min-h-[240px] flex-col gap-px overflow-y-auto p-2">
      {rows.length === 0 && (
        <div className="empty">
          {query ? "Nothing matches that." : "No command is available here."}
        </div>
      )}

      {found.tickets.length > 0 && <GroupLabel className="pt-2.5">Tickets</GroupLabel>}
      {found.tickets.map((ticket) => (
        <ResultRow
          key={ticket.id}
          row={rows[indexOf(ticket.id)]}
          index={indexOf(ticket.id)}
          active={active?.key === ticket.id}
          onHover={onHover}
          onOpen={onOpen}
        >
          <TicketIdentifier ticket={ticket} className="w-[62px] shrink-0 font-mono text-11 text-faint" />
          <StatusDot status={ticket.status} />
          <span className="min-w-0 flex-1 truncate">
            <Marked text={ticket.title} query={query} />
          </span>
          <span className="shrink-0 text-11 text-faint">
            {projects.find((project) => project.id === ticket.projectId)?.name ?? ""}
          </span>
        </ResultRow>
      ))}

      {/*
        * Documents are two groups and not one.
        *
        * A row here is a promise about what ↵ does, and the two kinds of document break
        * that promise apart: a page written in Kanso opens at `/docs/[id]`, still inside
        * the application; a Notion reference leaves for another tab in another product.
        * The three drawings that were considered:
        *
        * - One "Documents" group with a badge per row. Rejected: the badge is the only
        *   thing separating two rows that are otherwise identical — same glyph, same
        *   title treatment, adjacent — and the eye reads position long before it reads a
        *   nine-pixel word at the right margin. The confusion this whole fix is about is
        *   two things that look like one thing; a badge draws them as one thing.
        * - One group, sorted with the Kanso pages first. Rejected: an ordering nobody is
        *   told about is not information.
        * - Two headings. Position now carries the destination, which is the fact the
        *   reader needs before they press anything, and `search` already caps and counts
        *   the two apart so the groups cost nothing new.
        *
        * The trailing word stays as well, and is not redundant with the heading: the list
        * scrolls at 60vh and a row can be read with its heading off-screen, so each row
        * says on its own where ↵ is about to send it. `↗` is the only mark in this list
        * that means "leaves Kanso".
        */}
      {found.pages.length > 0 && <GroupLabel>Documents</GroupLabel>}
      {found.pages.map((page) => {
        const key = pageKey(page.id);
        const index = indexOf(key);
        return (
          <ResultRow
            key={key}
            row={rows[index]}
            index={index}
            active={active?.key === key}
            onHover={onHover}
            onOpen={onOpen}
          >
            <span aria-hidden className="w-[62px] shrink-0 text-center text-faint">
              ◈
            </span>
            <span className="min-w-0 flex-1 truncate">
              <Marked text={page.title} query={query} />
            </span>
            <span className="shrink-0 text-11 text-faint">Kanso</span>
          </ResultRow>
        );
      })}

      {found.notionRefs.length > 0 && <GroupLabel>Notion pages</GroupLabel>}
      {found.notionRefs.map((notionRef) => {
        const key = notionRefKey(notionRef.id);
        const index = indexOf(key);
        return (
          <ResultRow
            key={key}
            row={rows[index]}
            index={index}
            active={active?.key === key}
            onHover={onHover}
            onOpen={onOpen}
          >
            <span aria-hidden className="w-[62px] shrink-0 text-center text-faint">
              ◈
            </span>
            <span className="min-w-0 flex-1 truncate">
              <Marked text={notionRef.title ?? notionRef.notionPageId} query={query} />
            </span>
            <span className="shrink-0 text-11 text-faint">
              Notion <span aria-hidden>↗</span>
            </span>
          </ResultRow>
        );
      })}

      {found.commands.length > 0 && showCommandHeading && <GroupLabel>Commands</GroupLabel>}
      {found.commands.map((command) => (
        <ResultRow
          key={command.id}
          row={rows[indexOf(command.id)]}
          index={indexOf(command.id)}
          active={active?.key === command.id}
          onHover={onHover}
          onOpen={onOpen}
        >
          <span className="min-w-0 flex-1 truncate">
            <Marked text={command.label} query={query} />
          </span>
          {command.hint && <span className="shrink-0 text-11 text-faint">{command.hint}</span>}
        </ResultRow>
      ))}
    </div>
  );
}

/** One row. The three kinds differ only in what they put inside it. */
function ResultRow({
  row,
  index,
  active,
  onHover,
  onOpen,
  children,
}: {
  row: SearchRow | undefined;
  index: number;
  active: boolean;
  onHover: (index: number) => void;
  onOpen: (row: SearchRow, inPage: boolean) => void;
  children: React.ReactNode;
}) {
  if (!row) return null;
  return (
    <button
      type="button"
      data-testid="search-result"
      data-active={active}
      className={cn(
        "flex h-[38px] w-full items-center gap-2.5 rounded-md px-2.5 text-left text-13",
        active ? "bg-accent-soft" : "hover:bg-accent",
      )}
      onMouseEnter={() => onHover(index)}
      // `metaKey` too: opening a result in a new tab is what a reader expects of a link,
      // and shift is the drawing's own "en page".
      onClick={(event) => onOpen(row, event.shiftKey || event.metaKey)}
    >
      {children}
    </button>
  );
}

/** What was typed, marked. A `<mark>` and not a span: the emphasis is the meaning. */
function Marked({ text, query }: { text: string; query: string }) {
  return (
    <>
      {highlight(text, query).map((part, index) =>
        part.hit ? (
          <mark key={index} className="rounded-sm bg-accent-soft px-0.5 text-accent-ink">
            {part.text}
          </mark>
        ) : (
          <span key={index}>{part.text}</span>
        ),
      )}
    </>
  );
}
