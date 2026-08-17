"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { openTicketMode, ticketHref } from "@/lib/api";
import { usePreferences, useProjects, useSearchableTickets, useDocs } from "@/lib/queries";
import { cn } from "@/lib/utils";
import { useUi } from "@/store/ui";
import {
  countLabel,
  highlight,
  isPickerList,
  nextTab,
  search,
  SEARCH_TABS,
  type PaletteCommand,
  type SearchRow,
  type SearchTab,
} from "./search/results";
import { SearchPreview } from "./search/preview";
import { Backdrop } from "./overlays";
import { GroupLabel } from "./ui/group-label";
import { Kbd } from "./ui/kbd";
import { StatusDot } from "./ui/status-dot";

/**
 * Screen 06 — the global search.
 *
 * The palette and the search are one object. That is not a convenience: a second overlay
 * with its own field, its own keys and its own idea of what a result is would be the same
 * gesture taught twice. So what is typed here searches tickets, documents *and* commands
 * at once, with the drawing's preview pane on the right to decide without opening.
 *
 * The prop shape is deliberately unchanged. `app/page.tsx` passes `commands` and
 * `onClose` and is frozen for the fan-out; more importantly, `d` and `D` on the chart
 * route the dependency picker through this same overlay, and that has to keep working
 * exactly as it did. See `isPickerList`.
 */

/** The four labels of the tab strip, in `SEARCH_TABS` order. */
const TAB_LABELS: Record<SearchTab, string> = {
  all: "All",
  tickets: "Tickets",
  docs: "Documents",
  commands: "Commands",
};

export function CommandPalette({
  commands,
  onClose,
  only,
}: {
  commands: PaletteCommand[];
  onClose: () => void;
  /**
   * `"commands"` turns the search off. The dependency picker is detected without it —
   * `page.tsx` cannot pass it — but a caller that can say so should, and integration
   * replacing the sniff with this prop is a one-line change at one call site.
   */
  only?: "commands";
}) {
  const router = useRouter();
  const preferences = usePreferences();
  const projects = useProjects();
  const { select, open } = useUi();
  const [query, setQuery] = useState("");
  const [tab, setTab] = useState<SearchTab>("all");
  const [active, setActive] = useState(0);

  // Two lists of tickets on one screen with different consequences is the one thing this
  // rewrite must not do, so a picker searches nothing.
  const searching = only !== "commands" && !isPickerList(commands);

  // `enabled` on both: the picker pays for neither query, and neither does a ⌘K that is
  // closed again before anything is typed — these are two extra requests otherwise.
  const tickets = useSearchableTickets(searching);
  const docs = useDocs(searching);

  const found = useMemo(
    () =>
      search({
        query,
        tab,
        tickets: searching ? (tickets.data ?? []) : [],
        docs: searching ? (docs.data ?? []) : [],
        commands,
      }),
    [query, tab, searching, tickets.data, docs.data, commands],
  );

  const rows = found.rows;
  const current = rows[active];

  /**
   * Opening a result.
   *
   * `page` is `⇧↵` and the drawing says so; `↵` is `preferences.openTicket`, the column
   * screen 02 said this setting lives in. A document opens where it actually lives, which
   * is Notion — Kanso has no reader for one until slice B builds screen 07.
   */
  const openRow = (row: SearchRow | undefined, inPage: boolean) => {
    if (!row) return;
    if (row.kind === "command") {
      row.command.run();
      return;
    }
    if (row.kind === "doc") {
      if (row.doc.url) window.open(row.doc.url, "_blank", "noreferrer");
      onClose();
      return;
    }
    // The cursor follows what was opened either way: coming back from the ticket page
    // should land on the row that was just read, not on wherever the list was before.
    select(row.ticket.id);
    onClose();
    if (inPage || openTicketMode(preferences) === "page") {
      router.push(ticketHref(row.ticket.identifier));
    } else {
      open("detail");
    }
  };

  // Typing narrows the list, so the highlight goes back to the top — in the change
  // handler rather than an effect, which would render twice to undo a selection nobody
  // saw. The same applies to changing tab.
  const retype = (next: string) => {
    setQuery(next);
    setActive(0);
  };

  const retab = (next: SearchTab) => {
    setTab(next);
    setActive(0);
  };

  return (
    <Backdrop onClose={onClose} panelClassName="w-[min(820px,94vw)]">
      {/* The overlay is still in front of the whole application, sidebar included, so a
          test asking for "Tickets" needs somewhere to ask it of. `Backdrop` takes only a
          className, which is why the handle sits on this element rather than on the panel. */}
      <div data-testid="palette" className="flex">
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex items-center gap-2.5 px-[18px] py-3.5">
            <span aria-hidden className="text-15 text-faint">
              ⌕
            </span>
            <input
              className="min-w-0 flex-1 border-none bg-transparent p-0 text-15 text-foreground outline-none placeholder:text-faint"
              autoFocus
              aria-label={searching ? "Search tickets, documents and commands" : "Search commands"}
              /**
               * One string for both modes, and it still ends in the words it always did.
               * `keyboard.spec.ts` and `archive.spec.ts` reach this field by
               * `getByPlaceholder("Type a command…")` — a substring match — and those two
               * specs are not slice A's to edit. The precise wording of what this field
               * accepts lives in `aria-label`, which nothing keys on.
               */
              placeholder="Search, or type a command…"
              value={query}
              onChange={(event) => retype(event.target.value)}
              onKeyDown={(event) => {
                // The page answers bare keys on `window`; nothing typed here may also be
                // moving a cursor or changing a status underneath.
                event.stopPropagation();
                if (event.key === "ArrowDown") {
                  event.preventDefault();
                  setActive((index) => Math.min(index + 1, rows.length - 1));
                }
                if (event.key === "ArrowUp") {
                  event.preventDefault();
                  setActive((index) => Math.max(index - 1, 0));
                }
                if (event.key === "Tab" && searching) {
                  // Tab has no other job in a modal with one field, and the drawing puts
                  // the key beside the tab's own name for exactly this reason.
                  event.preventDefault();
                  retab(nextTab(tab));
                }
                if (event.key === "Enter") {
                  event.preventDefault();
                  openRow(current, event.shiftKey);
                }
                if (event.key === "Escape") onClose();
              }}
            />
            {searching && (
              <>
                <span className="text-11 text-faint">{TAB_LABELS[tab]}</span>
                <Kbd>tab</Kbd>
              </>
            )}
          </div>

          {/*
            * `role="group"` with `aria-pressed`, exactly like the view and zoom controls
            * in `app/page.tsx` — not `tablist`/`tab`, which promises a `tabpanel` this
            * strip does not have: the list below is one result list being filtered, not
            * four panels being switched between. It also means `.segmented`, which styles
            * the active button off `aria-pressed`, dresses this strip with no new CSS.
            */}
          {searching && (
            <div className="segmented mx-[18px] mb-2" role="group" aria-label="What to search">
              {SEARCH_TABS.map((candidate) => (
                <button
                  key={candidate}
                  type="button"
                  aria-pressed={tab === candidate}
                  onClick={() => retab(candidate)}
                >
                  {TAB_LABELS[candidate]}
                </button>
              ))}
            </div>
          )}

          <div className="h-px bg-border" />

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
                index={rows.findIndex((row) => row.key === ticket.id)}
                active={current?.key === ticket.id}
                onHover={setActive}
                onOpen={openRow}
                rows={rows}
              >
                <span className="w-[62px] shrink-0 font-mono text-11 text-faint">
                  {ticket.identifier}
                </span>
                <StatusDot status={ticket.status} />
                <span className="min-w-0 flex-1 truncate">
                  <Marked text={ticket.title} query={query} />
                </span>
                <span className="shrink-0 text-11 text-faint">
                  {projects.data?.find((project) => project.id === ticket.projectId)?.name ?? ""}
                </span>
              </ResultRow>
            ))}

            {found.docs.length > 0 && <GroupLabel>Documents</GroupLabel>}
            {found.docs.map((doc) => (
              <ResultRow
                key={doc.id}
                index={rows.findIndex((row) => row.key === doc.id)}
                active={current?.key === doc.id}
                onHover={setActive}
                onOpen={openRow}
                rows={rows}
              >
                <span aria-hidden className="w-[62px] shrink-0 text-center text-faint">
                  ◈
                </span>
                <span className="min-w-0 flex-1 truncate">
                  <Marked text={doc.title ?? doc.notionPageId} query={query} />
                </span>
                <span className="shrink-0 text-11 text-faint">Notion</span>
              </ResultRow>
            ))}

            {found.commands.length > 0 && searching && <GroupLabel>Commands</GroupLabel>}
            {found.commands.map((command) => (
              <ResultRow
                key={command.id}
                index={rows.findIndex((row) => row.key === command.id)}
                active={current?.key === command.id}
                onHover={setActive}
                onOpen={openRow}
                rows={rows}
              >
                <span className="min-w-0 flex-1 truncate">
                  <Marked text={command.label} query={query} />
                </span>
                {command.hint && <span className="shrink-0 text-11 text-faint">{command.hint}</span>}
              </ResultRow>
            ))}
          </div>

          <div className="h-px bg-border" />
          <div className="flex items-center gap-2.5 px-4 py-2.5 text-11 text-faint">
            <span>
              <Kbd>↑</Kbd> <Kbd>↓</Kbd> move
            </span>
            <span>
              <Kbd>↵</Kbd> open
            </span>
            {searching && (
              <span>
                <Kbd>⇧↵</Kbd> in page
              </span>
            )}
            <span className="flex-1" />
            <span data-testid="search-count" role="status">
              {countLabel(found.shown, found.total)}
            </span>
          </div>
        </div>

        {searching && <SearchPreview row={current} projects={projects.data ?? []} />}
      </div>
    </Backdrop>
  );
}

/** One row of the result list. The three kinds differ only in what they put inside it. */
function ResultRow({
  index,
  active,
  rows,
  onHover,
  onOpen,
  children,
}: {
  index: number;
  active: boolean;
  rows: SearchRow[];
  onHover: (index: number) => void;
  onOpen: (row: SearchRow | undefined, inPage: boolean) => void;
  children: React.ReactNode;
}) {
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
      // and shift is the drawing's own "in page".
      onClick={(event) => onOpen(rows[index], event.shiftKey || event.metaKey)}
    >
      {children}
    </button>
  );
}

/** What was typed, marked. `<mark>` and not a span: the emphasis is the meaning. */
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
